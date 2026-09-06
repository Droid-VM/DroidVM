# Contributing

## Sign-off

Every commit must carry a `Signed-off-by:` line certifying the
[Developer Certificate of Origin 1.1](https://developercertificate.org/):

    git commit -s

## What signing off means here

The DCO asks you to certify that you have the right to submit your work under
the license this project uses. For this project that license is the GNU GPL
**plus the additional permissions in `ADDITIONAL-PERMISSIONS`**, so a
contribution made under it carries those permissions too.

That is deliberate, and it is why this project asks for a DCO rather than a
CLA. The additional permissions let anyone relicense material from this project
in order to get it accepted into the upstream project it belongs in. If
contributions did not carry those permissions, every contribution would become
a piece of the tree that could never be sent upstream, and the permission would
quietly stop meaning anything as the project grew.

You keep your copyright. Nothing is assigned to anyone.

## Do not sign off on someone else's license

If you are contributing material you did not write -- code ported from another
project, a header copied from a kernel, a function translated from elsewhere --
say so in the file and keep that material under its own license. Do not put a
project SPDX tag on it. Several files in these repositories are in exactly that
position and are marked accordingly.
