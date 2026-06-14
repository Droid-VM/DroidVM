//! rtnetlink helpers. Idioms (connection, link/addr parsing, rt_priority,
//! route/rule) are lifted from pbridge's netlink.rs so behaviour matches the
//! proven daemon. Mutations return `Result<()>` (exit code); queries return
//! serializable rows that `main` prints as JSON.

use anyhow::{bail, Context, Result};
use futures::stream::TryStreamExt;
use netlink_packet_route::address::{AddressAttribute, AddressScope};
use netlink_packet_route::link::{
    BridgeStpState, InfoBridge, InfoBridgePort, InfoData, InfoKind, InfoPortData, InfoPortKind,
    InfoVlan, LinkAttribute, LinkInfo, LinkMessage, State,
};
use netlink_packet_route::neighbour::{
    NeighbourAddress, NeighbourAttribute, NeighbourFlags, NeighbourState,
};
use netlink_packet_route::route::{RouteProtocol, RouteScope};
use netlink_packet_route::rule::{RuleAction, RuleAttribute, RuleFlags};
use netlink_packet_route::AddressFamily;
use rtnetlink::{Handle, LinkBridge, LinkUnspec, RouteMessageBuilder};
use serde::Serialize;
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

pub struct Net {
    handle: Handle,
}

/// The only rtnetlink multicast group we ever join: RTM_NEWADDR/RTM_DELADDR
/// for IPv4. The host-IP set is *defined* as v4 addresses, so only address
/// events can change it; staying off the link/route/neigh/v6-addr groups
/// (all noisy and irrelevant) keeps daemon wakeups — and power — to a minimum.
const RTNLGRP_IPV4_IFADDR: u32 = 5;

// ----------------------------------------------------------------- query rows

/// `metric` is IFA_RT_PRIORITY — the field the system `ip -j` may not emit but
/// pbridge tags its proxy addresses with; netlink always carries it.
#[derive(Serialize)]
pub struct AddrRow {
    pub ifname: String,
    pub family: u8,
    pub local: String,
    pub prefixlen: u8,
    pub scope: String,
    pub metric: u32,
    pub noprefixroute: bool,
}

#[derive(Serialize)]
pub struct LinkRow {
    pub ifname: String,
    pub address: String,
    pub operstate: String,
    pub master: String,
    pub mtu: u32,
    pub kind: String,
}

#[derive(Serialize)]
pub struct NeighRow {
    pub dst: String,
    pub lladdr: String,
    pub dev: String,
    pub state: Vec<String>,
}

#[derive(Serialize)]
pub struct RuleRow {
    pub priority: u32,
    pub iif: Option<String>,
    pub table: u32,
    pub fwmark: Option<u32>,
    pub fwmask: Option<u32>,
    pub detached: bool,
    /// action is "lookup <table>" (vs blackhole/unreachable/...); only these
    /// carry a meaningful routing table.
    pub lookup: bool,
}

// ------------------------------------------------------------------- helpers

fn mac_to_string(b: &[u8]) -> String {
    b.iter()
        .map(|x| format!("{x:02x}"))
        .collect::<Vec<_>>()
        .join(":")
}

fn parse_mac(s: &str) -> Result<Vec<u8>> {
    let bytes: Result<Vec<u8>> = s
        .split(':')
        .map(|p| u8::from_str_radix(p, 16).with_context(|| format!("bad MAC: {s}")))
        .collect();
    let v = bytes?;
    if v.len() != 6 {
        bail!("MAC must be 6 octets: {s}");
    }
    Ok(v)
}

fn oper_to_string(s: &State) -> String {
    match s {
        State::Up => "UP",
        State::Down => "DOWN",
        State::LowerLayerDown => "LOWERLAYERDOWN",
        State::Testing => "TESTING",
        State::Dormant => "DORMANT",
        State::NotPresent => "NOTPRESENT",
        _ => "UNKNOWN",
    }
    .to_string()
}

fn neigh_states(s: &NeighbourState) -> Vec<String> {
    let name = match s {
        NeighbourState::Incomplete => "INCOMPLETE",
        NeighbourState::Reachable => "REACHABLE",
        NeighbourState::Stale => "STALE",
        NeighbourState::Delay => "DELAY",
        NeighbourState::Probe => "PROBE",
        NeighbourState::Failed => "FAILED",
        NeighbourState::Noarp => "NOARP",
        NeighbourState::Permanent => "PERMANENT",
        _ => "NONE",
    };
    vec![name.to_string()]
}

impl Net {
    pub fn connect() -> Result<Net> {
        let (conn, handle, _m) = rtnetlink::new_connection().context("netlink connect")?;
        tokio::spawn(conn);
        Ok(Net { handle })
    }

    async fn index_of(&self, name: &str) -> Result<u32> {
        let mut s = self
            .handle
            .link()
            .get()
            .match_name(name.to_string())
            .execute();
        match s.try_next().await {
            Ok(Some(m)) => Ok(m.header.index),
            Ok(None) => bail!("device not found: {name}"),
            Err(e) => Err(e).with_context(|| format!("get_link {name}")),
        }
    }

    /// index -> ifname for every link (addresses/neighbours carry only ifindex).
    async fn link_names(&self) -> Result<HashMap<u32, String>> {
        let mut map = HashMap::new();
        let mut s = self.handle.link().get().execute();
        while let Some(m) = s.try_next().await.context("link dump")? {
            map.insert(m.header.index, link_name(&m));
        }
        Ok(map)
    }

    // ---------------------------------------------------------- link mutations

    pub async fn link_add_bridge(&self, name: &str) -> Result<()> {
        self.handle
            .link()
            .add(LinkBridge::new(name).build())
            .execute()
            .await
            .context("link_add_bridge")
    }

    /// `ip link add link <parent> name <name> type vlan id <vid>`.
    pub async fn link_add_vlan(&self, parent: &str, name: &str, vid: u16) -> Result<()> {
        let pidx = self.index_of(parent).await?;
        let mut msg = LinkMessage::default();
        msg.attributes.push(LinkAttribute::IfName(name.to_string()));
        msg.attributes.push(LinkAttribute::Link(pidx));
        msg.attributes.push(LinkAttribute::LinkInfo(vec![
            LinkInfo::Kind(InfoKind::Vlan),
            LinkInfo::Data(InfoData::Vlan(vec![InfoVlan::Id(vid)])),
        ]));
        self.handle
            .link()
            .add(msg)
            .execute()
            .await
            .context("link_add_vlan")
    }

    pub async fn link_del(&self, name: &str) -> Result<()> {
        let idx = match self.index_of(name).await {
            Ok(i) => i,
            // already gone: idempotent
            Err(_) => return Ok(()),
        };
        self.handle
            .link()
            .del(idx)
            .execute()
            .await
            .context("link_del")
    }

    pub async fn link_set_state(&self, name: &str, up: bool) -> Result<()> {
        let idx = self.index_of(name).await?;
        let b = LinkUnspec::new_with_index(idx);
        let msg = if up { b.up().build() } else { b.down().build() };
        self.handle
            .link()
            .set(msg)
            .execute()
            .await
            .context("link_set_state")
    }

    pub async fn link_set_master(&self, iface: &str, master: Option<&str>) -> Result<()> {
        let idx = self.index_of(iface).await?;
        let midx = match master {
            Some(m) => self.index_of(m).await?,
            None => 0, // nomaster
        };
        let msg = LinkUnspec::new_with_index(idx).controller(midx).build();
        self.handle
            .link()
            .set(msg)
            .execute()
            .await
            .context("link_set_master")
    }

    pub async fn link_set_mac(&self, dev: &str, mac: &str) -> Result<()> {
        let idx = self.index_of(dev).await?;
        let msg = LinkUnspec::new_with_index(idx)
            .address(parse_mac(mac)?)
            .build();
        self.handle
            .link()
            .set(msg)
            .execute()
            .await
            .context("link_set_mac")
    }

    pub async fn link_set_stp(&self, bridge: &str, on: bool) -> Result<()> {
        let idx = self.index_of(bridge).await?;
        let mut msg = LinkMessage::default();
        msg.header.index = idx;
        let stp = if on {
            BridgeStpState::KernelStp
        } else {
            BridgeStpState::Disabled
        };
        msg.attributes.push(LinkAttribute::LinkInfo(vec![
            LinkInfo::Kind(InfoKind::Bridge),
            LinkInfo::Data(InfoData::Bridge(vec![InfoBridge::StpState(stp)])),
        ]));
        self.handle
            .link()
            .set(msg)
            .execute()
            .await
            .context("link_set_stp")
    }

    async fn set_port_flag(&self, dev: &str, port: InfoBridgePort) -> Result<()> {
        let idx = self.index_of(dev).await?;
        let mut msg = LinkMessage::default();
        msg.header.index = idx;
        msg.attributes.push(LinkAttribute::LinkInfo(vec![
            LinkInfo::PortKind(InfoPortKind::Bridge),
            LinkInfo::PortData(InfoPortData::BridgePort(vec![port])),
        ]));
        self.handle
            .link()
            .set(msg)
            .execute()
            .await
            .context("set_port_flag")
    }

    pub async fn link_set_isolated(&self, dev: &str, on: bool) -> Result<()> {
        self.set_port_flag(dev, InfoBridgePort::Isolated(on)).await
    }

    pub async fn link_set_locked(&self, dev: &str, on: bool) -> Result<()> {
        self.set_port_flag(dev, InfoBridgePort::Locked(on)).await
    }

    // ---------------------------------------------------------- addr mutations

    pub async fn addr_add(&self, dev: &str, ip: IpAddr, plen: u8) -> Result<()> {
        let idx = self.index_of(dev).await?;
        let mut req = self.handle.address().add(idx, ip, plen);
        // match `ip addr add`: a real IPv4 subnet broadcast (else directed
        // broadcast is lost); IPv6 has no broadcast.
        if let IpAddr::V4(v4) = ip {
            if plen < 31 {
                let host = u32::MAX >> plen;
                let brd = Ipv4Addr::from(u32::from(v4) | host);
                req.message_mut()
                    .attributes
                    .push(AddressAttribute::Broadcast(brd));
            }
        }
        req.execute().await.context("addr_add")
    }

    pub async fn addr_del(&self, dev: &str, ip: IpAddr, plen: u8) -> Result<()> {
        let idx = self.index_of(dev).await?;
        let msg = self
            .handle
            .address()
            .add(idx, ip, plen)
            .message_mut()
            .clone();
        match self.handle.address().del(msg).execute().await {
            Ok(()) => Ok(()),
            Err(rtnetlink::Error::NetlinkError(e))
                if e.raw_code() == -libc::EADDRNOTAVAIL || e.raw_code() == -libc::ENOENT =>
            {
                Ok(())
            }
            Err(e) => Err(e).context("addr_del"),
        }
    }

    // --------------------------------------------------------- route mutations

    /// `ip [-6] route add <dst>/<plen> dev <dev> table <table>` (onlink subnet).
    pub async fn route_add(&self, dev: &str, dst: IpAddr, plen: u8, table: u32) -> Result<()> {
        let oif = self.index_of(dev).await?;
        let msg = match dst {
            IpAddr::V4(d) => RouteMessageBuilder::<Ipv4Addr>::new()
                .destination_prefix(d, plen)
                .output_interface(oif)
                .table_id(table)
                .scope(RouteScope::Link)
                .protocol(RouteProtocol::Boot)
                .build(),
            IpAddr::V6(d) => RouteMessageBuilder::<Ipv6Addr>::new()
                .destination_prefix(d, plen)
                .output_interface(oif)
                .table_id(table)
                .scope(RouteScope::Link)
                .protocol(RouteProtocol::Boot)
                .build(),
        };
        match self.handle.route().add(msg).execute().await {
            Ok(()) => Ok(()),
            Err(rtnetlink::Error::NetlinkError(e)) if e.raw_code() == -libc::EEXIST => Ok(()),
            Err(e) => Err(e).context("route_add"),
        }
    }

    // ---------------------------------------------------------- rule mutations

    /// `ip [-4|-6] rule add from all iif <iif> lookup <table>` (idempotent).
    /// `.v4()`/`.v6()` yield distinct typed builders, so the family branch
    /// can't be factored out (it returns different types) — inline like pbridge.
    pub async fn rule_add(&self, v6: bool, iif: &str, table: u32) -> Result<()> {
        let req = self
            .handle
            .rule()
            .add()
            .input_interface(iif.to_string())
            .table_id(table)
            .action(RuleAction::ToTable);
        let res = if v6 {
            req.v6().execute().await
        } else {
            req.v4().execute().await
        };
        match res {
            Ok(()) => Ok(()),
            Err(rtnetlink::Error::NetlinkError(e)) if e.raw_code() == -libc::EEXIST => Ok(()),
            Err(e) => Err(e).context("rule_add"),
        }
    }

    /// `ip [-4|-6] rule del from all iif <iif> lookup <table>` (idempotent).
    pub async fn rule_del(&self, v6: bool, iif: &str, table: u32) -> Result<()> {
        let base = self
            .handle
            .rule()
            .add()
            .input_interface(iif.to_string())
            .table_id(table)
            .action(RuleAction::ToTable);
        let msg = if v6 {
            base.v6().message_mut().clone()
        } else {
            base.v4().message_mut().clone()
        };
        match self.handle.rule().del(msg).execute().await {
            Ok(()) => Ok(()),
            Err(rtnetlink::Error::NetlinkError(e))
                if e.raw_code() == -libc::ENOENT || e.raw_code() == -libc::ESRCH =>
            {
                Ok(())
            }
            Err(e) => Err(e).context("rule_del"),
        }
    }

    // ------------------------------------------------------------ fdb mutation

    /// `bridge fdb add <mac> dev <dev> master static`: a permanent forwarding
    /// entry managed by the bridge master (used for MAC security on locked
    /// ports). Idempotent.
    pub async fn fdb_add(&self, mac: &str, dev: &str) -> Result<()> {
        let idx = self.index_of(dev).await?;
        // add_bridge presets family=AF_BRIDGE; "master static" = NTF_MASTER
        // (Controller) + NUD_NOARP.
        let res = self
            .handle
            .neighbours()
            .add_bridge(idx, &parse_mac(mac)?)
            .state(NeighbourState::Noarp)
            .flags(NeighbourFlags::Controller)
            .execute()
            .await;
        match res {
            Ok(()) => Ok(()),
            Err(rtnetlink::Error::NetlinkError(e)) if e.raw_code() == -libc::EEXIST => Ok(()),
            Err(e) => Err(e).context("fdb_add"),
        }
    }

    // -------------------------------------------------------------- queries

    pub async fn addr_list(&self, dev: Option<&str>, metric: Option<u32>) -> Result<Vec<AddrRow>> {
        let names = self.link_names().await?;
        let mut req = self.handle.address().get();
        if let Some(d) = dev {
            req = req.set_link_index_filter(self.index_of(d).await?);
        }
        let mut s = req.execute();
        let mut out = Vec::new();
        while let Some(m) = s.try_next().await.context("addr dump")? {
            let family = match m.header.family {
                AddressFamily::Inet => 4u8,
                AddressFamily::Inet6 => 6u8,
                _ => continue,
            };
            let scope = match m.header.scope {
                AddressScope::Universe => "global",
                AddressScope::Link => "link",
                AddressScope::Host => "host",
                _ => "other",
            }
            .to_string();
            let mut local: Option<IpAddr> = None;
            let mut rt = 0u32;
            let mut npr = false;
            for a in &m.attributes {
                match a {
                    AddressAttribute::Local(x) => local = Some(*x),
                    AddressAttribute::Address(x) => {
                        if local.is_none() {
                            local = Some(*x);
                        }
                    }
                    AddressAttribute::RoutePriority(p) => rt = *p,
                    AddressAttribute::Flags(f) => {
                        use netlink_packet_route::address::AddressFlags;
                        npr = f.contains(AddressFlags::Noprefixroute);
                    }
                    _ => {}
                }
            }
            if let Some(want) = metric {
                if rt != want {
                    continue;
                }
            }
            let local = match local {
                Some(x) => x,
                None => continue,
            };
            out.push(AddrRow {
                ifname: names.get(&m.header.index).cloned().unwrap_or_default(),
                family,
                local: local.to_string(),
                prefixlen: m.header.prefix_len,
                scope,
                metric: rt,
                noprefixroute: npr,
            });
        }
        Ok(out)
    }

    /// The phone's own reachable IPv4 addresses, sorted and de-duplicated:
    /// scope-global v4 on interfaces whose name starts with one of `prefixes`
    /// (the daemon's Wi-Fi/cellular/VPN/ethernet/tethering allowlist), with
    /// bridge devices and `exclude_metric`-tagged addresses (pbridge offload)
    /// dropped. This is the single definition of the host-IP set, shared by
    /// the one-shot `host-ips` and the streaming `monitor-addr`.
    pub async fn host_ipv4(
        &self,
        prefixes: &[String],
        exclude_metric: Option<u32>,
    ) -> Result<Vec<String>> {
        // one link dump → ifindex's name and whether it's a bridge
        let mut names: HashMap<u32, String> = HashMap::new();
        let mut is_bridge: HashMap<u32, bool> = HashMap::new();
        let mut ls = self.handle.link().get().execute();
        while let Some(m) = ls.try_next().await.context("link dump")? {
            let mut bridge = false;
            for a in &m.attributes {
                if let LinkAttribute::LinkInfo(infos) = a {
                    for li in infos {
                        if let LinkInfo::Kind(InfoKind::Bridge) = li {
                            bridge = true;
                        }
                    }
                }
            }
            names.insert(m.header.index, link_name(&m));
            is_bridge.insert(m.header.index, bridge);
        }
        let mut out = std::collections::BTreeSet::new();
        let mut s = self.handle.address().get().execute();
        while let Some(m) = s.try_next().await.context("addr dump")? {
            if !matches!(m.header.family, AddressFamily::Inet) {
                continue;
            }
            // global scope only: link-local (169.254) / host (127) aren't
            // addresses a forward could land on
            if !matches!(m.header.scope, AddressScope::Universe) {
                continue;
            }
            let idx = m.header.index;
            if *is_bridge.get(&idx).unwrap_or(&false) {
                continue;
            }
            let ifname = names.get(&idx).cloned().unwrap_or_default();
            if !prefixes.iter().any(|p| ifname.starts_with(p.as_str())) {
                continue;
            }
            let mut local: Option<IpAddr> = None;
            let mut rt = 0u32;
            for a in &m.attributes {
                match a {
                    AddressAttribute::Local(x) => local = Some(*x),
                    AddressAttribute::Address(x) => {
                        if local.is_none() {
                            local = Some(*x);
                        }
                    }
                    AddressAttribute::RoutePriority(p) => rt = *p,
                    _ => {}
                }
            }
            if let Some(want) = exclude_metric {
                if rt == want {
                    continue;
                }
            }
            if let Some(IpAddr::V4(v4)) = local {
                out.insert(v4.to_string());
            }
        }
        Ok(out.into_iter().collect())
    }

    /// `ip -j link show [master <br>] [type bridge]`.
    pub async fn link_list(&self, master: Option<&str>, bridge_only: bool) -> Result<Vec<LinkRow>> {
        let names = self.link_names().await?;
        let want_master = match master {
            Some(m) => Some(self.index_of(m).await?),
            None => None,
        };
        let mut s = self.handle.link().get().execute();
        let mut out = Vec::new();
        while let Some(m) = s.try_next().await.context("link dump")? {
            let mut address = String::new();
            let mut operstate = "UNKNOWN".to_string();
            let mut master_idx: Option<u32> = None;
            let mut mtu = 0u32;
            let mut kind = String::new();
            for a in &m.attributes {
                match a {
                    LinkAttribute::Address(b) => address = mac_to_string(b),
                    LinkAttribute::OperState(s) => operstate = oper_to_string(s),
                    LinkAttribute::Controller(i) => master_idx = Some(*i),
                    LinkAttribute::Mtu(v) => mtu = *v,
                    LinkAttribute::LinkInfo(infos) => {
                        for li in infos {
                            if let LinkInfo::Kind(k) = li {
                                kind = format!("{k:?}").to_lowercase();
                            }
                        }
                    }
                    _ => {}
                }
            }
            if let Some(wm) = want_master {
                if master_idx != Some(wm) {
                    continue;
                }
            }
            if bridge_only && kind != "bridge" {
                continue;
            }
            out.push(LinkRow {
                ifname: link_name(&m),
                address,
                operstate,
                master: master_idx
                    .and_then(|i| names.get(&i).cloned())
                    .unwrap_or_default(),
                mtu,
                kind,
            });
        }
        Ok(out)
    }

    /// `ip neigh show dev <dev>` (both families).
    pub async fn neigh_list(&self, dev: &str) -> Result<Vec<NeighRow>> {
        let idx = self.index_of(dev).await?;
        let mut out = Vec::new();
        for v6 in [false, true] {
            let mut s = self
                .handle
                .neighbours()
                .get()
                .set_family(if v6 {
                    rtnetlink::IpVersion::V6
                } else {
                    rtnetlink::IpVersion::V4
                })
                .execute();
            while let Some(m) = s.try_next().await.context("neigh dump")? {
                if m.header.ifindex != idx {
                    continue;
                }
                let mut dst = String::new();
                let mut lladdr = String::new();
                for a in &m.attributes {
                    match a {
                        NeighbourAttribute::Destination(NeighbourAddress::Inet(d)) => {
                            dst = d.to_string()
                        }
                        NeighbourAttribute::Destination(NeighbourAddress::Inet6(d)) => {
                            dst = d.to_string()
                        }
                        NeighbourAttribute::LinkLayerAddress(b) => lladdr = mac_to_string(b),
                        _ => {}
                    }
                }
                if dst.is_empty() {
                    continue;
                }
                out.push(NeighRow {
                    dst,
                    lladdr,
                    dev: dev.to_string(),
                    state: neigh_states(&m.header.state),
                });
            }
        }
        Ok(out)
    }

    /// `ip [-6] rule show`.
    pub async fn rule_list(&self, v6: bool) -> Result<Vec<RuleRow>> {
        let mut s = self
            .handle
            .rule()
            .get(if v6 {
                rtnetlink::IpVersion::V6
            } else {
                rtnetlink::IpVersion::V4
            })
            .execute();
        let mut out = Vec::new();
        while let Some(m) = s.try_next().await.context("rule dump")? {
            let mut priority = 0u32;
            let mut iif = None;
            let mut table = m.header.table as u32;
            let mut fwmark = None;
            let mut fwmask = None;
            for a in &m.attributes {
                match a {
                    RuleAttribute::Priority(p) => priority = *p,
                    RuleAttribute::Iifname(n) => iif = Some(n.clone()),
                    RuleAttribute::Table(t) => table = *t,
                    RuleAttribute::FwMark(v) => fwmark = Some(*v),
                    RuleAttribute::FwMask(v) => fwmask = Some(*v),
                    _ => {}
                }
            }
            // iproute2 prints [detached] when the iif/oif device is gone.
            let detached = m
                .header
                .flags
                .intersects(RuleFlags::IifDetached | RuleFlags::OifDetached);
            out.push(RuleRow {
                priority,
                iif,
                table,
                fwmark,
                fwmask,
                detached,
                lookup: m.header.action == RuleAction::ToTable,
            });
        }
        Ok(out)
    }
}

/// Streams the host-IPv4 set: emits the initial set, then re-evaluates and
/// re-emits only when it actually changes, driven by RTNLGRP_IPV4_IFADDR
/// notifications. `emit` is called with each new set (e.g. printing a JSON
/// line); the connection's own diff means the caller is woken only on real
/// changes. Runs until the netlink connection closes.
pub async fn monitor_host_ipv4(
    prefixes: &[String],
    exclude_metric: Option<u32>,
    mut emit: impl FnMut(&[String]) -> Result<()>,
) -> Result<()> {
    use futures::StreamExt;
    use netlink_sys::AsyncSocket;

    let (mut conn, handle, mut messages) =
        rtnetlink::new_connection().context("netlink connect")?;
    conn.socket_mut()
        .socket_mut()
        .add_membership(RTNLGRP_IPV4_IFADDR)
        .context("join RTNLGRP_IPV4_IFADDR")?;
    tokio::spawn(conn);
    let net = Net { handle };

    let mut last = net.host_ipv4(prefixes, exclude_metric).await?;
    emit(&last)?;
    // every message arrives on RTNLGRP_IPV4_IFADDR, so any of them is a v4
    // address add/del — no need to inspect it, just re-evaluate the set.
    while messages.next().await.is_some() {
        // coalesce a burst (a DHCP renew is a del+add, etc.) into one recompute
        let _ = tokio::time::timeout(std::time::Duration::from_millis(200), async {
            while messages.next().await.is_some() {}
        })
        .await;
        let cur = net.host_ipv4(prefixes, exclude_metric).await?;
        if cur != last {
            emit(&cur)?;
            last = cur;
        }
    }
    Ok(())
}

fn link_name(m: &LinkMessage) -> String {
    m.attributes
        .iter()
        .find_map(|a| match a {
            LinkAttribute::IfName(n) => Some(n.clone()),
            _ => None,
        })
        .unwrap_or_default()
}
