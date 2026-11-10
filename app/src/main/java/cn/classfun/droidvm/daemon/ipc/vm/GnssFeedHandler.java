// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
// Additional permissions apply; see ADDITIONAL-PERMISSIONS in the repository root.
package cn.classfun.droidvm.daemon.ipc.vm;

import androidx.annotation.NonNull;

import com.google.auto.service.AutoService;

import cn.classfun.droidvm.daemon.gnss.GnssHub;
import cn.classfun.droidvm.daemon.server.ClientRequest;
import cn.classfun.droidvm.daemon.server.RequestHandler;
import cn.classfun.droidvm.lib.gnss.GnssFix;
import cn.classfun.droidvm.lib.gnss.GnssSatellite;
import cn.classfun.droidvm.lib.store.vm.GnssSource;

/**
 * Takes the phone's receiver and location from the app's feeder ({@code GnssFeeder}, in the
 * peripheral foreground service) into {@link GnssHub}. Not per VM: the phone has one receiver,
 * and every VM with a GNSS row hears the same sky.
 *
 * <p>Params, each part optional: {@code receiver} {fix, sky, sky_age} -- a fix carries its own
 * GNSS time; sky is the satellite rows, sky_age how long ago in ms -- and {@code location}
 * {fix, age}. Answers how many running ports take each, which is all the feeder can learn about
 * whether anyone is listening.</p>
 */
@AutoService(RequestHandler.class)
public final class GnssFeedHandler extends RequestHandler {
    @NonNull
    @Override
    public String getName() {
        return "gnss_feed";
    }

    @Override
    public void handle(@NonNull ClientRequest request) throws Exception {
        var params = request.getParams();
        var hub = GnssHub.get();
        hub.onFeed();
        var receiver = params.optJSONObject("receiver");
        if (receiver != null) {
            var sky = receiver.has("sky")
                ? GnssSatellite.fromJson(receiver.optJSONArray("sky")) : null;
            hub.onReceiver(GnssFix.fromJson(receiver.optJSONObject("fix")), sky,
                receiver.optLong("sky_age", 0));
        }
        var location = params.optJSONObject("location");
        if (location != null) {
            var fix = GnssFix.fromJson(location.optJSONObject("fix"));
            if (fix != null) hub.onLocation(fix, location.optLong("age", 0));
        }
        request.res().put("receiver_ports", hub.portsFor(GnssSource.RECEIVER));
        request.res().put("location_ports", hub.portsFor(GnssSource.LOCATION));
    }
}
