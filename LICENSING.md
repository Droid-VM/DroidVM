# Licensing

This repository holds two kinds of material and they are licensed differently.

## Material inherited from upstream



Every file that came from an upstream project stays under that project's
license. Nothing here relicenses it, and modifications to those files do not
relicense them either — a patched upstream file is still an upstream file.

## Material written for DroidVM

Files carrying `SPDX-License-Identifier: GPL-3.0-or-later` are DroidVM work
and are licensed under the GNU GPL, version 3 or later, **with the
additional permissions in `ADDITIONAL-PERMISSIONS`**.

Those permissions exist so this work can go upstream. They let anyone
relicense it under the terms an upstream project requires, for the purpose of
getting it merged there — and only for that purpose. Once upstream publishes
it, upstream's license governs that copy.

## Third-party material that is neither

None. The app is DroidVM work throughout; `app/src/main/java/cn/classfun/droidvm`
is the only source tree and nothing is vendored into it.

## Contributing

See `CONTRIBUTING.md`. Sign-off is required; there is no CLA.
