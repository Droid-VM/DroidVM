//! netbox — minimal netlink CLI for the DroidVM daemon.
//!
//! The daemon used to shell out to the system `ip`/`bridge` (iproute2). Those
//! vary per OEM and some are too old to emit `IFA_RT_PRIORITY` in `-j` JSON —
//! exactly the field pbridge tags its offload-proxy addresses with. netbox does
//! the same operations straight over rtnetlink, with our own JSON schema, so
//! behaviour is identical on every device. Most invocations do one op and
//! exit (mutations report via exit code, queries print JSON to stdout); the
//! lone exception is `monitor-addr`, which streams a JSON line per change
//! until killed.

use anyhow::Result;
use clap::{Parser, Subcommand};
use std::net::IpAddr;

mod nl;
mod tap;

#[derive(Parser)]
#[command(name = "netbox", version, about, disable_help_subcommand = true)]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Create a bridge device.
    LinkAddBridge { name: String },
    /// Create an 802.1q VLAN subinterface on a parent device.
    LinkAddVlan {
        #[arg(long)]
        link: String,
        #[arg(long)]
        name: String,
        #[arg(long)]
        id: u16,
    },
    /// Create a persistent TAP (mode tap, vnet_hdr).
    LinkAddTap { name: String },
    /// Delete a link by name (idempotent).
    LinkDel { name: String },
    /// Bring a link up or down.
    LinkSetState {
        name: String,
        #[arg(value_parser = ["up", "down"])]
        state: String,
    },
    /// Enslave a link into a bridge.
    LinkSetMaster { iface: String, master: String },
    /// Detach a link from its bridge.
    LinkSetNomaster { iface: String },
    /// Set a link's MAC address.
    LinkSetMac { dev: String, mac: String },
    /// Toggle STP on a bridge.
    LinkSetStp {
        bridge: String,
        #[arg(value_parser = on_off)]
        on: bool,
    },
    /// Toggle bridge-port isolation.
    LinkSetIsolated {
        dev: String,
        #[arg(value_parser = on_off)]
        on: bool,
    },
    /// Toggle bridge-port MAC locking.
    LinkSetLocked {
        dev: String,
        #[arg(value_parser = on_off)]
        on: bool,
    },
    /// Add an address to a device (CIDR).
    AddrAdd { dev: String, cidr: String },
    /// Remove an address from a device (CIDR; idempotent).
    AddrDel { dev: String, cidr: String },
    /// List addresses as JSON; optional device and IFA_RT_PRIORITY filters.
    AddrList {
        #[arg(long)]
        dev: Option<String>,
        #[arg(long)]
        metric: Option<u32>,
    },
    /// Add an onlink subnet route in a table.
    RouteAdd {
        #[arg(long)]
        dev: String,
        #[arg(long)]
        dst: String,
        #[arg(long)]
        table: u32,
        #[arg(long = "v6")]
        v6: bool,
    },
    /// Add `from all iif <iif> lookup <table>` (idempotent).
    RuleAdd {
        #[arg(long)]
        iif: String,
        #[arg(long)]
        table: u32,
        #[arg(long = "v6")]
        v6: bool,
    },
    /// Remove `from all iif <iif> lookup <table>` (idempotent).
    RuleDel {
        #[arg(long)]
        iif: String,
        #[arg(long)]
        table: u32,
        #[arg(long = "v6")]
        v6: bool,
    },
    /// Add a static fdb entry on a bridge master.
    FdbAdd { mac: String, dev: String },
    /// List links as JSON; optional master / bridge-only filters.
    LinkList {
        #[arg(long)]
        master: Option<String>,
        #[arg(long = "type-bridge")]
        type_bridge: bool,
    },
    /// List a device's neighbours as JSON.
    NeighList { dev: String },
    /// List policy routing rules as JSON.
    RuleList {
        #[arg(long = "v6")]
        v6: bool,
    },
    /// Print the phone's own reachable IPv4 addresses once as `{"v4":[...]}`.
    HostIps {
        /// Interface-name prefix to include (repeatable): wlan, rmnet_data, …
        #[arg(long)]
        iface: Vec<String>,
        /// Drop addresses tagged with this IFA_RT_PRIORITY (pbridge offload).
        #[arg(long)]
        exclude_metric: Option<u32>,
    },
    /// Stream `{"v4":[...]}` whenever the reachable IPv4 set changes. Subscribes
    /// to RTNLGRP_IPV4_IFADDR and re-emits only on an actual change; runs until
    /// killed.
    MonitorAddr {
        #[arg(long)]
        iface: Vec<String>,
        #[arg(long)]
        exclude_metric: Option<u32>,
    },
}

fn on_off(s: &str) -> Result<bool, String> {
    match s {
        "on" => Ok(true),
        "off" => Ok(false),
        _ => Err(format!("expected on|off, got {s:?}")),
    }
}

/// Split "addr/plen" into its address and prefix length.
fn parse_cidr(cidr: &str) -> Result<(IpAddr, u8)> {
    let (a, p) = cidr
        .split_once('/')
        .ok_or_else(|| anyhow::anyhow!("not a CIDR: {cidr}"))?;
    Ok((a.parse()?, p.parse()?))
}

fn main() {
    std::process::exit(match run() {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("netbox: {e:#}");
            1
        }
    });
}

fn run() -> Result<()> {
    let cli = Cli::parse();
    // tuntap is a plain ioctl — no netlink runtime needed.
    if let Cmd::LinkAddTap { name } = &cli.cmd {
        return tap::add_tap(name);
    }
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    rt.block_on(async {
        match cli.cmd {
            // long-running: its own connection joins a multicast group
            Cmd::MonitorAddr {
                iface,
                exclude_metric,
            } => nl::monitor_host_ipv4(&iface, exclude_metric, print_host_ips).await,
            // everything else is one op against a fresh connection, then exit
            cmd => {
                let net = nl::Net::connect()?;
                run_oneshot(&net, cmd).await
            }
        }
    })
}

async fn run_oneshot(net: &nl::Net, cmd: Cmd) -> Result<()> {
    match cmd {
        Cmd::LinkAddBridge { name } => net.link_add_bridge(&name).await,
        Cmd::LinkAddVlan { link, name, id } => net.link_add_vlan(&link, &name, id).await,
        Cmd::LinkAddTap { .. } => unreachable!("handled before runtime"),
        Cmd::LinkDel { name } => net.link_del(&name).await,
        Cmd::LinkSetState { name, state } => net.link_set_state(&name, state == "up").await,
        Cmd::LinkSetMaster { iface, master } => net.link_set_master(&iface, Some(&master)).await,
        Cmd::LinkSetNomaster { iface } => net.link_set_master(&iface, None).await,
        Cmd::LinkSetMac { dev, mac } => net.link_set_mac(&dev, &mac).await,
        Cmd::LinkSetStp { bridge, on } => net.link_set_stp(&bridge, on).await,
        Cmd::LinkSetIsolated { dev, on } => net.link_set_isolated(&dev, on).await,
        Cmd::LinkSetLocked { dev, on } => net.link_set_locked(&dev, on).await,
        Cmd::AddrAdd { dev, cidr } => {
            let (ip, plen) = parse_cidr(&cidr)?;
            net.addr_add(&dev, ip, plen).await
        }
        Cmd::AddrDel { dev, cidr } => {
            let (ip, plen) = parse_cidr(&cidr)?;
            net.addr_del(&dev, ip, plen).await
        }
        Cmd::AddrList { dev, metric } => print_json(&net.addr_list(dev.as_deref(), metric).await?),
        Cmd::RouteAdd {
            dev,
            dst,
            table,
            v6: _,
        } => {
            let (ip, plen) = parse_cidr(&dst)?;
            net.route_add(&dev, ip, plen, table).await
        }
        Cmd::RuleAdd { iif, table, v6 } => net.rule_add(v6, &iif, table).await,
        Cmd::RuleDel { iif, table, v6 } => net.rule_del(v6, &iif, table).await,
        Cmd::FdbAdd { mac, dev } => net.fdb_add(&mac, &dev).await,
        Cmd::LinkList {
            master,
            type_bridge,
        } => print_json(&net.link_list(master.as_deref(), type_bridge).await?),
        Cmd::NeighList { dev } => print_json(&net.neigh_list(&dev).await?),
        Cmd::RuleList { v6 } => print_json(&net.rule_list(v6).await?),
        Cmd::HostIps {
            iface,
            exclude_metric,
        } => print_host_ips(&net.host_ipv4(&iface, exclude_metric).await?),
        Cmd::MonitorAddr { .. } => unreachable!("handled in run()"),
    }
}

fn print_json<T: serde::Serialize>(rows: &T) -> Result<()> {
    println!("{}", serde_json::to_string(rows)?);
    Ok(())
}

/// One JSON line `{"v4":[...]}` flushed immediately — the daemon reads these
/// from the monitor's stdout, so the flush is what makes a change visible
/// without waiting on stdio block-buffering.
fn print_host_ips(v4: &[String]) -> Result<()> {
    use std::io::Write;
    let line = serde_json::to_string(&serde_json::json!({ "v4": v4 }))?;
    let mut out = std::io::stdout().lock();
    writeln!(out, "{line}")?;
    out.flush()?;
    Ok(())
}
