/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.spatial;

import com.geophile.z.Space;
import com.geophile.z.SpatialObject;
import com.geophile.z.spatialobject.jts.JTS;
import com.geophile.z.spatialobject.jts.JTSSpatialObject;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKBReader;
import com.vividsolutions.jts.io.WKBWriter;

/*

The lat/lon coordinate system is

- latitude: -90.0 to +90.0
- longitude: -180.0 to 180.0 with wraparound

The interleave pattern is [lon, lat, lon, lat, ..., lon], reflecting the fact that longitude covers a numeric range
twice that of latitude.

 */

public class Spatial
{
    public static Space createLatLonSpace()
    {
        int[] interleave = new int[LAT_BITS + LON_BITS];
        int dimension = 1; // Start with lon, as described above
        for (int d = 0; d < LAT_BITS + LON_BITS; d++) {
            interleave[d] = dimension;
            dimension = 1 - dimension;
        }
        return Space.newSpace(new double[]{MIN_LAT, MIN_LON},
                              new double[]{MAX_LAT, MAX_LON},
                              new int[]{LAT_BITS, LON_BITS},
                              interleave);
    }

    /** @deprecated */
    public static long shuffle(Space space, double x, double y)
    {
        com.geophile.z.spatialobject.d2.Point point = new com.geophile.z.spatialobject.d2.Point(x, y);
        long[] zValues = new long[1];
        space.decompose(point, zValues);
        long z = zValues[0];
        assert z != Space.Z_NULL;
        return z;
    }

    public static void shuffle(Space space, SpatialObject spatialObject, long[] zs)
    {
        space.decompose(spatialObject, zs);
    }

    public static Object serializeIfSpatial(Object object)
    {
        return
            object instanceof JTSSpatialObject
            ? serialize((JTSSpatialObject) object)
            : object;
    }

    public static byte[] serialize(JTSSpatialObject spatialObject)
    {
        return io.get().writer().write(spatialObject.geometry());
    }

    public static SpatialObject deserialize(Space space, byte[] bytes) throws ParseException
    {
        Geometry geometry = io.get().reader().read(bytes);
        return
            geometry instanceof Point
            ? JTS.spatialObject(space, (Point) geometry)
            : JTS.spatialObject(space, geometry);
    }

    public static final int LAT_LON_DIMENSIONS = 2;
    public static final double MIN_LAT = -90;
    public static final double MAX_LAT = 90;
    public static final double MIN_LON = -180;
    public static final double MAX_LON = 180;
    private static final int LAT_BITS = 28;
    private static final int LON_BITS = 29;
    private static final ThreadLocal<IO> io =
        new ThreadLocal<IO>()
        {
            @Override
            protected IO initialValue()
            {
                return new IO();
            }
        };

    // Inner classes

    private static class IO
    {
        public WKBReader reader()
        {
            if (reader == null) {
                reader = new WKBReader(factory);
            }
            return reader;
        }

        public WKBWriter writer()
        {
            if (writer == null) {
                writer = new WKBWriter();
            }
            return writer;
        }

        private final GeometryFactory factory = new GeometryFactory();
        private WKBReader reader;
        private WKBWriter writer;
    }
}
