package org.openpowerwatch.sync;

/** Расчёт восхода и заката по алгоритму NOAA. Координаты Егорьевска. */
public final class SunTimes {

    private static final double RAD = Math.PI / 180.0;
    private static final double LAT = 55.38;
    private static final double LON = 39.03;

    /** Возвращает пару [восход, закат] в миллисекундах для суток,
        содержащих момент nowMs, либо null (не бывает для наших широт). */
    public static long[] forDay(long nowMs) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(nowMs);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 12);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);

        double day = cal.getTimeInMillis() / 86400000.0 + 2440587.5;
        double n = day - 2451545.0;

        double L = (280.460 + 0.9856474 * n) % 360;
        double g = (357.528 + 0.9856003 * n) * RAD;
        double lambda = (L + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)) * RAD;
        double eps = (23.439 - 0.0000004 * n) * RAD;
        double decl = Math.asin(Math.sin(eps) * Math.sin(lambda));

        double cosH = (Math.sin(-0.833 * RAD) - Math.sin(LAT * RAD) * Math.sin(decl)) /
                      (Math.cos(LAT * RAD) * Math.cos(decl));
        if (cosH > 1 || cosH < -1) return null;
        double H = Math.acos(cosH);

        int tz = java.util.TimeZone.getDefault().getOffset(nowMs);
        long solarNoon = (nowMs - tz) - ((nowMs - tz) % 86400000L) + 43200000L - (long)(LON * 240000);
        long halfDay = (long) (Math.toDegrees(H) * 240000);
        return new long[]{solarNoon - halfDay, solarNoon + halfDay};
    }

    /** Ближайший будущий восход или закат после момента nowMs. */
    public static long nextEvent(long nowMs) {
        long best = -1;
        for (int d = 0; d <= 1; d++) {
            long[] t = forDay(nowMs + d * 86400000L);
            if (t == null) continue;
            if (t[0] > nowMs && (best == -1 || t[0] < best)) best = t[0];
            if (t[1] > nowMs && (best == -1 || t[1] < best)) best = t[1];
        }
        return best;
    }

    private SunTimes() {}
}

