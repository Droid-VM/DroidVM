// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
 *   <li>{@link #OPENGL} -- GL calls are proxied. Guest runs mesa's virgl gallium driver;
 *   <li>{@link #VULKAN} -- Vulkan calls are proxied. On gfxstream that is its Vulkan
 *   <li>{@link #NATIVE} -- only kernel ioctls are proxied. The guest runs its REAL driver
