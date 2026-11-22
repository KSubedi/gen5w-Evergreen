# Debugging the hotspot's channel switching

Wireless CarPlay and Android Auto run over the head unit's own Wi-Fi hotspot (`hostapd` on
`wlan0`, apMode 2). In owner logs from a US EV6 on 260507 (2026-11-21, seven captures on
Evergreen 1.2.0.103-beta.1), that hotspot **changed Wi-Fi channel by itself during drives**,
and the phone dropped shortly after:

| Capture | What the owner saw | What the logs show |
|---|---|---|
| 3 | "CarPlay crashed", came back with turn-by-turn | 09:10:24 no video for 10 s (DiPO's screen timeout), 09:10:26 **channel switch to 5785 MHz**, 09:10:38 iAP2 link down, 09:10:39 session ended, 09:10:43 reconnected |
| 4 | CarPlay crash, HUD stopped | **channel switch to 5220 MHz**, the phone disconnected 7 s later (deauthentication, reason 4: inactivity) |
| 6 | CarPlay crash | **channel switch to 5745 MHz** about 8 s before the capture |
| 2, 7 | Picture frozen, HUD kept updating | switches to 5180 and 5785 MHz earlier in the same drives; the freeze itself was before the captured minute |

Channels seen: 5180, 5220, 5745 and 5785 MHz (36, 44, 149, 157), all 20 MHz wide and none of
them a radar (DFS) channel, so this is not a forced radar move. Nothing on the Android side
requested the switches: the kernel reports them from the Wi-Fi chip's firmware
(`wl_cfg80211_ch_switch_notify`), and `hostapd` logs channel-interference statistics every
second (`chanspec=… obss=… glitch=… badplcp=… knoise=…`). The working theory is that the
hotspot moves to a quieter channel when it measures interference (Broadcom's channel
interference mitigation, or an automatic channel selection in `hostapd`), and that the phone
sometimes does not follow the move, or the video stream stalls while it does.

Evergreen does not configure the hotspot: no part of the package touches `hostapd`, its
configuration or the Wi-Fi chip's settings. Whatever switches the channel is the head unit's
own. The head unit's Wi-Fi *client* is a separate known problem (it shares the radio and
drops projection; see the README's known quirks); in these logs it was off.

## What we still need to know

1. Where the switching is turned on: a `hostapd.conf` setting (`channel=0`/`acs_*`,
   `chanlist`, interference options), a `wl` driver setting (`chanim_mode`, interference
   mitigation), or a vendor daemon.
2. Whether every channel switch is followed by a stall or drop, or only some (the AR trace
   records both since 1.3.0.113).
3. Whether pinning the channel is possible from the package without a firmware change.

## Step 1: capture the hotspot's settings (any Evergreen build)

`Evergreen-AR-hotspot.zip` (in the AR controls zip, release 1.3.0.113 and later) is a
read-only NEMESIS kit. It changes nothing on the head unit and works with plain Evergreen as
well as the AR builds.

1. Unzip it onto an empty USB drive so `NEMESIS.sh` and `NEMESIS.data` are at its root.
2. Best right after a drive with a freeze or drop (the kernel log only keeps the recent past).
   Parked, insert the drive and confirm the NEMESIS prompt.
3. When NEMESIS says "Export complete", take `HOTSPOT/hotspot.txt` off the drive and send it.

What the report holds:

- `hostapd`, `wpa_supplicant` and `dnsmasq` command lines (flags and file paths only);
- the hotspot configuration files, with every value that names or unlocks the network
  (`ssid`, passphrases, keys, BSSID, addresses, PINs) replaced by `<removed>`;
- Wi-Fi properties (`getprop`), redacted the same way;
- the Wi-Fi chip's own view, if the unit has a `wl` tool: channel, band, bandwidth, country,
  `chanim_mode`, `chanim_stats`, interference mitigation, radar/DFS state, OBSS coexistence,
  and the number of connected phones (never their addresses);
- `/proc/net/wireless`;
- the kernel's channel switches, disconnects (with their reason codes), access-point starts
  and radar events since boot, with hardware addresses replaced by `<mac>`;
- `hostapd`'s last 400 interference-statistics lines;
- the last 200 CarPlay screen-timeout and session lines.

It never copies the network name, the password or a hardware address. Check the file before
sharing it anyway.

## Step 2: record switches during drives (AR builds 1.3.0.113+)

The AR diagnostic recorder now copies the hotspot's kernel events into the AR trace every
15 s, so they line up with the CarPlay screen records:

```
EvergreenAR: v113 wifi kernel_s=3977.580579 event=ch_switch freq=5785
EvergreenAR: v113 wifi kernel_s=4121.925019 event=deauth reason=4
EvergreenAR: v113 wifi kernel_s=18015.159439 event=deauth_by_phone reason=8
```

Events: `ch_switch` (with `freq` in MHz), `deauth`/`disassoc` (the hotspot removed the phone),
`deauth_by_phone`/`disassoc_by_phone` (the phone left), `ap_up`/`ap_down`. The kernel time is
seconds since the head unit started. No hardware address is ever logged.

After the drive, export with `Evergreen-AR-get-logs-<version>.zip` and run:

```
python3 -I tools/analyze-ar-log.py AR-DEBUG-FINISHED
```

The report lists the hotspot events, the channels switched to, every CarPlay stall (screen
timeout) with any channel switch within 60 s of it, which recovery step brought the picture
back and how long it took (see notes/carplay-ar.md, 1.3.0.113).

## Step 3: manual checks with adb or a root shell

On a unit with adb or a NEMESIS root script, these read-only commands show the same things
live (run them while CarPlay is connected; don't share the network name or addresses):

```
dmesg | grep -e ch_switch -e WLC_E_ -e 'AP/GO'          # switches and disconnects since boot
logcat -d -s hostapd:V | grep chanspec | tail -20       # interference statistics per second
wl -i wlan0 chanspec                                    # current channel (if wl exists)
wl -i wlan0 chanim_mode                                 # interference mitigation mode
wl -i wlan0 chanim_stats                                # interference per channel
ps | grep -e hostapd -e wpa_supplicant                  # then cat /proc/<pid>/cmdline
```

Reading the `hostapd` statistics: `chanspec` is the channel (`0xd095` is 149, `0xd09d` 157,
`0xd024` 36, `0xd02c` 44; `0xd0..` means 5 GHz, 20 MHz), `obss` the share of airtime used
by other networks, `glitch`/`badplcp` receive errors per second, `knoise` the noise floor in
dBm, `idle` free airtime. One capture showed `glitch` jump from 0 to 1470 and `badplcp` to 215
within two seconds, which is the kind of reading that could make the hotspot change channel.

## What a fix could look like (not done yet)

Only once Step 1 shows where the switching comes from:

- a fixed channel in the hotspot configuration, if `hostapd` chooses it;
- turning the chip's interference-driven channel changes off for the projection hotspot, if
  `wl chanim_mode` (or similar) drives them;
- otherwise, nothing at the hotspot level: the 1.3.0.113 recovery (key frame, then a screen
  borrow-and-return) shortens the freezes that follow a switch, but cannot prevent the drop.

Any such change would go through the package's install/uninstall snapshots like every other
file it touches, and needs a car test before release.
