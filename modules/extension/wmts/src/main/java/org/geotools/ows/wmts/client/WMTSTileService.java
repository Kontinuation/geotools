/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2017, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.ows.wmts.client;

import static org.geotools.tile.impl.ScaleZoomLevelMatcher.getProjectedEnvelope;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.http.HTTPClient;
import org.geotools.http.HTTPClientFinder;
import org.geotools.ows.wms.CRSEnvelope;
import org.geotools.ows.wmts.WMTSHelper;
import org.geotools.ows.wmts.WMTSSpecification;
import org.geotools.ows.wmts.model.TileMatrix;
import org.geotools.ows.wmts.model.TileMatrixSet;
import org.geotools.ows.wmts.model.TileMatrixSetLink;
import org.geotools.ows.wmts.model.WMTSLayer;
import org.geotools.ows.wmts.model.WMTSServiceType;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.tile.Tile;
import org.geotools.tile.TileFactory;
import org.geotools.tile.TileService;
import org.geotools.tile.impl.ScaleZoomLevelMatcher;
import org.geotools.util.logging.Logging;
import org.locationtech.jts.geom.Envelope;
import org.opengis.geometry.BoundingBox;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

/**
 * A tile service for a single matrix set of a WMTS servers.
 *
 * <p>An instance of this class is tied to a single layer, style and matrixset.
 *
 * <p>Use WebMapTileServer to negotiate capabilities, or specifying other properties
 *
 * @author ian
 * @author Emanuele Tajariol (etj at geo-solutions dot it)
 */
public class WMTSTileService extends TileService {

    protected static final Logger LOGGER = Logging.getLogger(WMTSTileService.class);

    @Deprecated public static final String DIMENSION_TIME = "time";

    @Deprecated public static final String DIMENSION_ELEVATION = "elevation";

    public static final String EXTRA_HEADERS = "HEADERS";

    private static final TileFactory tileFactory = new WMTSTileFactory();

    private String tileMatrixSetName = "";

    private double[] scaleList;

    private TileMatrixSet matrixSet;

    private final WMTSLayer layer;

    private String layerName;

    private String styleName = ""; // Default style is ""

    private ReferencedEnvelope envelope;

    private final TileURLBuilder urlBuilder;

    private WMTSServiceType type = WMTSServiceType.REST;

    private String format = "image/png";

    private Map<String, String> dimensions = new HashMap<>();

    private Map<String, Object> extrainfo = new HashMap<>();

    /**
     * create a service directly with out parsing the capabilities again.
     *
     * <p>This constructor should not be used. A proper formatted templateURL should be used
     * instead.
     *
     * @param templateURL - where to ask for tiles
     * @param type - KVP or REST
     * @param layer - the layer to request
     * @param styleName - name of the style to use?
     * @param tileMatrixSet - the tile matrix set to use
     */
    public WMTSTileService(
            String templateURL,
            WMTSServiceType type,
            WMTSLayer layer,
            String styleName,
            TileMatrixSet tileMatrixSet) {
        this(templateURL, type, layer, styleName, tileMatrixSet, HTTPClientFinder.createClient());
    }

    /**
     * create a service directly with out parsing the capabilities again.
     *
     * <p>This constructor should not be used. A proper formatted templateURL should be used
     * instead.
     *
     * @param templateURL - where to ask for tiles
     * @param type - KVP or REST
     * @param layer - layer to request
     * @param styleName - name of the style to use?
     * @param tileMatrixSet - matrixset
     * @param client - HttpClient instance to use for Tile requests.
     */
    public WMTSTileService(
            String templateURL,
            WMTSServiceType type,
            WMTSLayer layer,
            String styleName,
            TileMatrixSet tileMatrixSet,
            HTTPClient client) {
        this(
                prepareWithLayerStyleTileMatrixSet(
                        templateURL,
                        type,
                        layer.getName(),
                        styleName,
                        tileMatrixSet.getIdentifier()),
                layer,
                tileMatrixSet,
                client);
    }

    private static String prepareWithLayerStyleTileMatrixSet(
            String templateURL,
            WMTSServiceType type,
            String layerName,
            String styleName,
            String tileMatrixSet) {
        switch (type) {
            case KVP:
                return WMTSHelper.appendQueryString(
                        templateURL,
                        WMTSSpecification.GetTileRequest.getKVPparams(
                                layerName, styleName, tileMatrixSet, "image/png"));
            case REST:
                templateURL = WMTSHelper.replaceToken(templateURL, "layer", layerName);
                templateURL = WMTSHelper.replaceToken(templateURL, "style", styleName);
                templateURL = WMTSHelper.replaceToken(templateURL, "tilematrixset", tileMatrixSet);
                return templateURL;
            default:
                throw new IllegalArgumentException("Unexpected WMTS Service type " + type);
        }
    }

    /**
     * Creates a WMTSTileService based on the templateURL. Using extent given by layer and
     * tileMatrixSet.
     *
     * @param templateURL
     * @param layer
     * @param tileMatrixSet
     */
    public WMTSTileService(String templateURL, WMTSLayer layer, TileMatrixSet tileMatrixSet) {
        this(templateURL, layer, tileMatrixSet, HTTPClientFinder.createClient());
    }

    /**
     * create a service directly with out parsing the capabilties again.
     *
     * @param templateURL - where to ask for tiles
     * @param layer - layer to request
     * @param tileMatrixSet - matrixset
     * @param client - HttpClient instance to use for Tile requests.
     */
    public WMTSTileService(
            String templateURL, WMTSLayer layer, TileMatrixSet tileMatrixSet, HTTPClient client) {
        super("wmts", templateURL, client);

        this.layer = layer;
        this.tileMatrixSetName = tileMatrixSet.getIdentifier();
        this.envelope = new ReferencedEnvelope(layer.getLatLonBoundingBox());
        this.scaleList = buildScaleList(tileMatrixSet);
        this.urlBuilder = new TileURLBuilder(templateURL);
        setMatrixSet(tileMatrixSet);
    }

    private static double[] buildScaleList(TileMatrixSet tileMatrixSet) {

        double[] scaleList = new double[tileMatrixSet.size()];
        int j = 0;
        for (TileMatrix tm : tileMatrixSet.getMatrices()) {
            scaleList[j++] = tm.getDenominator();
        }

        return scaleList;
    }

    protected ReferencedEnvelope getReqExtentInTileCrs(ReferencedEnvelope requestedExtent) {

        CoordinateReferenceSystem reqCrs = requestedExtent.getCoordinateReferenceSystem();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(
                    "orig request bbox :"
                            + requestedExtent
                            + " "
                            + reqCrs.getCoordinateSystem().getAxis(0).getDirection()
                            + " ("
                            + reqCrs.getName()
                            + ")");
        }

        ReferencedEnvelope reqExtentInTileCrs = null;
        for (CRSEnvelope layerEnv : layer.getLayerBoundingBoxes()) {
            if (CRS.equalsIgnoreMetadata(reqCrs, layerEnv.getCoordinateReferenceSystem())) {
                // crop req extent according to layer bbox
                requestedExtent = requestedExtent.intersection(new ReferencedEnvelope(layerEnv));
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Layer CRS match: cropping request bbox :" + requestedExtent);
                }
                break;
            } else {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(
                            "Layer CRS not matching: "
                                    + "req:"
                                    + reqCrs.getName()
                                    + " cov:"
                                    + layerEnv.getCoordinateReferenceSystem().getName());
                }
            }
        }

        CoordinateReferenceSystem tileCrs = this.matrixSet.getCoordinateReferenceSystem();

        if (!CRS.equalsIgnoreMetadata(tileCrs, requestedExtent.getCoordinateReferenceSystem())) {
            try {
                reqExtentInTileCrs = requestedExtent.transform(tileCrs, true);
            } catch (TransformException | FactoryException ex) {
                LOGGER.log(
                        Level.WARNING,
                        "Requested extent can't be projected to tile CRS ("
                                + reqCrs.getCoordinateSystem().getName()
                                + " -> "
                                + tileCrs.getCoordinateSystem().getName()
                                + ") :"
                                + ex.getMessage());

                // maybe the req area is too wide for the data; let's try an
                // inverse transformation
                try {
                    ReferencedEnvelope covExtentInReqCrs = envelope.transform(reqCrs, true);
                    requestedExtent = requestedExtent.intersection(covExtentInReqCrs);

                } catch (TransformException | FactoryException ex2) {
                    LOGGER.log(Level.WARNING, "Incompatible CRS: " + ex2.getMessage());
                    return null; // should throw
                }
            }
        } else {
            reqExtentInTileCrs = requestedExtent;
        }

        if (reqExtentInTileCrs == null) {
            if (LOGGER.isLoggable(Level.FINE))
                LOGGER.log(Level.FINE, "Requested extent not in tile CRS range");
            return null;
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(
                    Level.FINE,
                    "tile crs req bbox :"
                            + reqExtentInTileCrs
                            + " "
                            + reqExtentInTileCrs
                                    .getCoordinateReferenceSystem()
                                    .getCoordinateSystem()
                                    .getAxis(0)
                                    .getDirection()
                            + " ("
                            + reqExtentInTileCrs.getCoordinateReferenceSystem().getName()
                            + ")");
        }

        ReferencedEnvelope coverageEnvelope = getBounds();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(
                    Level.FINE,
                    "coverage bbox :"
                            + coverageEnvelope
                            + " "
                            + coverageEnvelope
                                    .getCoordinateReferenceSystem()
                                    .getCoordinateSystem()
                                    .getAxis(0)
                                    .getDirection()
                            + " ("
                            + coverageEnvelope.getCoordinateReferenceSystem().getName()
                            + ")");
        }

        ReferencedEnvelope requestEnvelopeWGS84;

        boolean sameCRS =
                CRS.equalsIgnoreMetadata(
                        coverageEnvelope.getCoordinateReferenceSystem(),
                        reqExtentInTileCrs.getCoordinateReferenceSystem());
        if (sameCRS) {
            if (!coverageEnvelope.intersects((BoundingBox) reqExtentInTileCrs)) {
                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.log(Level.FINE, "Extents do not intersect (sameCRS))");
                return null;
            }
        } else {
            ReferencedEnvelope dataEnvelopeWGS84;
            try {
                dataEnvelopeWGS84 = coverageEnvelope.transform(DefaultGeographicCRS.WGS84, true);

                requestEnvelopeWGS84 = requestedExtent.transform(DefaultGeographicCRS.WGS84, true);

                if (!dataEnvelopeWGS84.intersects((BoundingBox) requestEnvelopeWGS84)) {
                    if (LOGGER.isLoggable(Level.FINE))
                        LOGGER.log(Level.FINE, "Extents do not intersect");
                    return null;
                }
            } catch (TransformException | FactoryException e) {
                throw new RuntimeException(e);
            }
        }

        return reqExtentInTileCrs;
    }

    @Override
    public Set<Tile> findTilesInExtent(
            ReferencedEnvelope requestedExtent,
            double scaleFactor,
            boolean recommendedZoomLevel,
            int maxNumberOfTiles) {

        Set<Tile> ret = Collections.emptySet();

        ReferencedEnvelope reqExtentInTileCrs = getReqExtentInTileCrs(requestedExtent);
        if (reqExtentInTileCrs == null) {
            if (LOGGER.isLoggable(Level.FINE))
                LOGGER.log(Level.FINE, "No valid extents, no Tiles will be returned.");
            return ret;
        }

        WMTSTileFactory tileFactory = (WMTSTileFactory) getTileFactory();

        ScaleZoomLevelMatcher zoomLevelMatcher = null;
        try {
            zoomLevelMatcher =
                    getZoomLevelMatcher(
                            reqExtentInTileCrs,
                            matrixSet.getCoordinateReferenceSystem(),
                            scaleFactor);

        } catch (FactoryException | TransformException e) {
            throw new RuntimeException(e);
        }

        int zl = getZoomLevelFromMapScale(zoomLevelMatcher, scaleFactor);
        WMTSZoomLevel zoomLevel = tileFactory.getZoomLevel(zl, this);
        long maxNumberOfTilesForZoomLevel = zoomLevel.getMaxTileNumber();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(
                    Level.FINE,
                    "Zoom level:"
                            + zl
                            + "["
                            + zoomLevel.getMaxTilePerColNumber()
                            + " x "
                            + zoomLevel.getMaxTilePerRowNumber()
                            + "]");
        }

        Set<Tile> tileList =
                new HashSet<>((int) Math.min(maxNumberOfTiles, maxNumberOfTilesForZoomLevel));

        double ulLon, ulLat;
        // Let's get upper-left corner coords
        CRS.AxisOrder aorder = CRS.getAxisOrder(reqExtentInTileCrs.getCoordinateReferenceSystem());
        switch (aorder) {
            case EAST_NORTH:
                ulLon = reqExtentInTileCrs.getMinX();
                ulLat = reqExtentInTileCrs.getMaxY();
                break;
            case NORTH_EAST:
                if (LOGGER.isLoggable(Level.FINE)) LOGGER.log(Level.FINE, "Inverted tile coords!");
                ulLon = reqExtentInTileCrs.getMinY();
                ulLat = reqExtentInTileCrs.getMaxX();
                break;
            default:
                LOGGER.log(Level.WARNING, "unexpected axis order " + aorder);
                return ret;
        }

        // The first tile which covers the upper-left corner
        Tile firstTile = tileFactory.findUpperLeftTile(ulLon, ulLat, zoomLevel, this);

        if (firstTile == null) {
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.log(
                        Level.INFO,
                        "First tile not available at x:"
                                + reqExtentInTileCrs.getMinX()
                                + " y:"
                                + reqExtentInTileCrs.getMaxY()
                                + " at "
                                + zoomLevel);
            }

            return ret;
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(
                    Level.FINE,
                    "Adding first tile "
                            + firstTile.getId()
                            + " "
                            + firstTile.getExtent()
                            + " ("
                            + firstTile.getExtent().getCoordinateReferenceSystem().getName()
                            + ")");
        }

        addTileToCache(firstTile);
        tileList.add(firstTile);

        Tile firstTileOfRow = firstTile;
        Tile movingTile = firstTile;

        do { // Loop column
            do { // Loop row

                // get the next tile right of this one
                Tile rightNeighbour =
                        tileFactory.findRightNeighbour(
                                movingTile, this); // movingTile.getRightNeighbour();

                if (rightNeighbour == null) { // no more tiles to the right
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(Level.FINE, "No tiles on the right of " + movingTile.getId());
                    }

                    break;
                }

                // Check if the new tile is still part of the extent
                boolean intersects =
                        reqExtentInTileCrs.intersects((Envelope) rightNeighbour.getExtent());
                if (intersects) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(Level.FINE, "Adding right neighbour " + rightNeighbour.getId());
                    }

                    addTileToCache(rightNeighbour);
                    tileList.add(rightNeighbour);

                    movingTile = rightNeighbour;
                } else {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(
                                Level.FINE,
                                "Right neighbour out of extents " + rightNeighbour.getId());
                    }

                    break;
                }
                if (tileList.size() > maxNumberOfTiles) {
                    LOGGER.warning(
                            "Reached tile limit of "
                                    + maxNumberOfTiles
                                    + ". Returning the tiles collected so far.");
                    return tileList;
                }
            } while (tileList.size() < maxNumberOfTilesForZoomLevel);

            // get the next tile under the first one of the row
            Tile lowerNeighbour = tileFactory.findLowerNeighbour(firstTileOfRow, this);

            if (lowerNeighbour == null) { // no more tiles to the right
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "No more tiles below " + firstTileOfRow.getId());
                }

                break;
            }

            // Check if the new tile is still part of the extent
            boolean intersects =
                    reqExtentInTileCrs.intersects((Envelope) lowerNeighbour.getExtent());

            if (intersects) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "Adding lower neighbour " + lowerNeighbour.getId());
                }

                addTileToCache(lowerNeighbour);
                tileList.add(lowerNeighbour);

                firstTileOfRow = movingTile = lowerNeighbour;
            } else {
                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.log(
                            Level.FINE, "Lower neighbour out of extents" + lowerNeighbour.getId());
                break;
            }
        } while (tileList.size() < maxNumberOfTilesForZoomLevel);

        return tileList;
    }

    /**
     * Add a tile to the cache.
     *
     * <p>At the moment we are delegating the cache to the super class, which handles the cache as a
     * soft cache. The soft cache has an un-controllable time to live, could last a split seconds or
     * 100 years. However, WMTS services normally come with caching headers of some sort, e.g., do
     * not cache, or keep for 1 hour, or 6 months and so on.
     *
     * <p>TODO: The code should account for that.
     */
    @Override
    protected Tile addTileToCache(Tile tile) {
        return super.addTileToCache(tile);
    }

    /** @return the type */
    @Deprecated
    public WMTSServiceType getType() {
        return type;
    }

    /** @param type the type to set */
    @Deprecated
    public void setType(WMTSServiceType type) {
        this.type = type;
    }

    /** @return the layerName */
    @Deprecated
    public String getLayerName() {
        return layerName;
    }

    /** @return the styleName */
    @Deprecated
    public String getStyleName() {
        return styleName;
    }

    /** @param styleName the styleName to set */
    @Deprecated
    public void setStyleName(String styleName) {
        this.styleName = styleName;
    }

    @Override
    public double[] getScaleList() {
        return scaleList;
    }

    @Override
    public ReferencedEnvelope getBounds() {
        return envelope;
    }

    @Override
    public CoordinateReferenceSystem getProjectedTileCrs() {
        return matrixSet.getCoordinateReferenceSystem();
    }

    @Override
    public TileFactory getTileFactory() {
        return tileFactory;
    }

    /** @return the tileMatrixSetName */
    @Deprecated
    public String getTileMatrixSetName() {
        return tileMatrixSetName;
    }

    /** @param tileMatrixSetName the tileMatrixSetName to set */
    @Deprecated
    public void setTileMatrixSetName(String tileMatrixSetName) {
        if (tileMatrixSetName == null || tileMatrixSetName.isEmpty()) {
            throw new IllegalArgumentException("Tile matrix set name cannot be null");
        }

        this.tileMatrixSetName = tileMatrixSetName;
    }

    public TileMatrixSetLink getMatrixSetLink() {
        return layer.getTileMatrixLinks().get(tileMatrixSetName);
    }

    /** @return the templateURL */
    @Deprecated
    public String getTemplateURL() {
        return getBaseUrl();
    }

    /** @param templateURL the templateURL to set */
    @Deprecated
    public void setTemplateURL(String templateURL) {}

    TileURLBuilder getURLBuilder() {
        return urlBuilder;
    }

    /** */
    public TileMatrix getTileMatrix(int zoomLevel) {
        if (matrixSet == null) {
            throw new RuntimeException("TileMatrix is not set in WMTSService");
        }
        return matrixSet.getMatrices().get(zoomLevel);
    }

    /** @return the matrixSet */
    public TileMatrixSet getMatrixSet() {
        return matrixSet;
    }

    /** @param matrixSet the matrixSet to set */
    public void setMatrixSet(TileMatrixSet matrixSet) {
        this.matrixSet = matrixSet;
        scaleList = new double[matrixSet.size()];
        int j = 0;
        for (TileMatrix tm : matrixSet.getMatrices()) {
            scaleList[j++] = tm.getDenominator();
        }
    }

    /** @return */
    @Deprecated
    public String getFormat() {
        return format;
    }

    /**
     * @param format the format to set
     * @deprecated include in templateURL
     */
    @Deprecated
    public void setFormat(String format) {
        this.format = format;
    }

    /** */
    public WMTSZoomLevel getZoomLevel(int zoom) {
        return new WMTSZoomLevel(zoom, this);
    }

    /** @deprecated Dimensions should be a part of templateUrl */
    @Deprecated
    public Map<String, String> getDimensions() {
        return dimensions;
    }

    /**
     * A place to put any Header that should be sent in http calls.
     *
     * @return
     */
    public Map<String, Object> getExtrainfo() {
        return extrainfo;
    }

    private ScaleZoomLevelMatcher getZoomLevelMatcher(
            ReferencedEnvelope requestExtent, CoordinateReferenceSystem crsTiles, double scale)
            throws FactoryException, TransformException {

        CoordinateReferenceSystem crsMap = requestExtent.getCoordinateReferenceSystem();

        // Transformation: MapCrs -> TileCrs
        MathTransform transformMapToTile = CRS.findMathTransform(crsMap, crsTiles);

        // Transformation: TileCrs -> MapCrs (needed for the blank tiles)
        MathTransform transformTileToMap = CRS.findMathTransform(crsTiles, crsMap);

        // Get the mapExtent in the tiles CRS
        ReferencedEnvelope mapExtentTileCrs =
                getProjectedEnvelope(requestExtent, crsTiles, transformMapToTile);

        return new ScaleZoomLevelMatcher(
                crsMap,
                crsTiles,
                transformMapToTile,
                transformTileToMap,
                mapExtentTileCrs,
                requestExtent,
                scale) {
            @Override
            public int getZoomLevelFromScale(TileService service, double[] tempScaleList) {
                double min = Double.MAX_VALUE;
                double scaleFactor = getScale();
                int zoomLevel = 0;
                for (int i = scaleList.length - 1; i >= 0; i--) {
                    final double v = scaleList[i];
                    final double diff = Math.abs(v - scaleFactor);
                    if (diff < min) {
                        min = diff;
                        zoomLevel = i;
                    }
                }
                return zoomLevel;
            }
        };
    }
}
