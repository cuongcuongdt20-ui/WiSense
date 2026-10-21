package vn.wisense.server.auth;

import java.util.Set;
import vn.wisense.common.protocol.Msg;

/**
 * Quyền của một phiên đã đăng nhập: được xem cảm biến nào, có được điều khiển (SET_CONFIG, SET_SENSOR) không.
 * sensors = null nghĩa là mọi cảm biến.
 */
public record Access(String user, boolean admin, Set<Integer> sensors) {
    /** Server chạy --no-auth: ai nối vào cũng được toàn quyền như trước. */
    public static final Access ALL = new Access(null, true, null);

    public Access {
        sensors = sensors == null ? null : Set.copyOf(sensors);
    }

    public boolean canSee(int sensor) {
        return sensors == null || sensors.contains(sensor);
    }

    public String level() {
        return admin ? Msg.ACCESS_ADMIN : Msg.ACCESS_VIEWER;
    }
}
