//! TAP creation. `ip tuntap add` is not a netlink op — it is a TUNSETIFF ioctl
//! on /dev/net/tun — so it lives apart from the rtnetlink helpers.

use anyhow::{bail, Context, Result};
use std::ffi::CString;
use std::os::fd::AsRawFd;

const IFNAMSIZ: usize = 16;
// <linux/if_tun.h> — _IOW('T', 202/203, int). The ioctl request arg is
// c_ulong on glibc but c_int on musl/bionic, so cast with `as _` at the call.
const TUNSETIFF: u32 = 0x4004_54ca;
const TUNSETPERSIST: u32 = 0x4004_54cb;
const IFF_TAP: libc::c_short = 0x0002;
const IFF_NO_PI: libc::c_short = 0x1000;
const IFF_VNET_HDR: libc::c_short = 0x4000;

#[repr(C)]
struct Ifreq {
    name: [libc::c_char; IFNAMSIZ],
    flags: libc::c_short,
    _pad: [u8; 22],
}

/// `ip tuntap add dev <name> mode tap vnet_hdr`: a persistent TAP with the
/// virtio net header, exactly what the daemon's VM taps require.
pub fn add_tap(name: &str) -> Result<()> {
    if name.len() >= IFNAMSIZ {
        bail!("tap name too long: {name}");
    }
    let f = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open("/dev/net/tun")
        .context("open /dev/net/tun")?;
    let fd = f.as_raw_fd();
    let mut ifr: Ifreq = unsafe { std::mem::zeroed() };
    for (i, b) in CString::new(name)?.as_bytes().iter().enumerate() {
        ifr.name[i] = *b as libc::c_char;
    }
    ifr.flags = IFF_TAP | IFF_NO_PI | IFF_VNET_HDR;
    if unsafe { libc::ioctl(fd, TUNSETIFF as _, &mut ifr as *mut Ifreq) } < 0 {
        return Err(std::io::Error::last_os_error()).context("TUNSETIFF");
    }
    if unsafe { libc::ioctl(fd, TUNSETPERSIST as _, 1 as libc::c_int) } < 0 {
        return Err(std::io::Error::last_os_error()).context("TUNSETPERSIST");
    }
    Ok(())
}
