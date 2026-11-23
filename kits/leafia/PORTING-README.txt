Ultimate Wireless Projection - porting kit (read-only)

This kit collects what's needed to bring Ultimate Wireless Projection
(wireless CarPlay and Android Auto, with navigation on the AR HUD) to your
Hyundai, Kia or Genesis. It changes nothing on the car.

1. Copy everything in this zip to the root of an empty exFAT or FAT32 USB
   drive with at least 200 MB free. No other NEMESIS files on the drive.
2. NEMESIS (1.15 or newer) must already be installed on the head unit.
3. Park with the car on (in READY for an EV or hybrid) and wait until the
   head unit has fully started. Plug in the drive and approve the NEMESIS
   prompt.
4. Wait for "Porting data saved" (1-2 minutes), then unplug the drive.
5. On a computer, open UWP-PORT/NEXT-STEPS.txt on the drive:

   UWP-PORT/attach-to-issue   Firmware ids, checksums and feature flags.
                              Safe to post. Zip this folder and attach it
                              to a port request:
   https://github.com/KSubedi/ultimate-wireless-projection/issues/new?template=port-request.yml

   UWP-PORT/send-privately    The car's stock system files (Hyundai/Kia
                              apps, libraries and framework), needed to
                              build the port. Don't post these publicly.
                              The maintainer will reply on your issue with
                              how to send them, or say they aren't needed
                              if your firmware is already known.

In the issue, also say whether your car has a head-up display, and if so
whether its settings offer an AR mode.

Never collected: logs, accounts, paired phone data, Wi-Fi passwords, firmware
key files, the vehicle security partition, VIN or serial numbers.
