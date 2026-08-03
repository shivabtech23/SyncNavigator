package com.shiv.syncnavigator.sdl

/**
 * SDL PROOF OF CONCEPT — hardware validation track only.
 *
 * This class must exist, must be declared in the manifest, and must stay empty.
 * The SDL library requires every SDL-enabled app to ship its own subclass of the
 * router service. Whichever installed app has the newest router version wins and
 * hosts the single Bluetooth connection to the head unit, multiplexing it to all
 * SDL apps on the device.
 *
 * Note the fully-qualified supertype: the subclass has the same simple name as
 * its parent, which Kotlin cannot resolve without it. This mirrors how the SDL
 * documentation names it, and renaming would only trade one confusion for another.
 */
class SdlRouterService : com.smartdevicelink.transport.SdlRouterService()
