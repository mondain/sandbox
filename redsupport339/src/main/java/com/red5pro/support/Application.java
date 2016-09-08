package com.red5pro.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.red5.logging.Red5LoggerFactory;
import org.red5.server.adapter.MultiThreadedApplicationAdapter;
import org.red5.server.api.IConnection;
import org.red5.server.api.Red5;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.scope.ScopeType;
import org.red5.server.api.stream.IBroadcastStream;
import org.red5.server.util.ScopeUtils;
import org.slf4j.Logger;

/**
 * Application providing a means to switch source streams feeding a single stream.
 * 
 * @author Paul Gregoire
 */
public class Application extends MultiThreadedApplicationAdapter {

    private static Logger log = Red5LoggerFactory.getLogger(Application.class, "redsupport339");

    /**
     * Shared execution of runnables.
     */
    private static ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Map holding the "stations" linked to their respective scope / room.
     */
    private ConcurrentMap<IScope, Station> stations = new ConcurrentHashMap<>();

    @Override
    public boolean appStart(IScope app) {
        log.info("Starting app: {}", app.getName());
        // create a Station for the room on start
        if (!stations.containsKey(app)) {
            // create a new station
            Station station = new Station(app);
            // create the outgoing feed
            if (station.createFeed()) {
                log.info("Station instanced successfully");
                // register in the map
                stations.put(app, station);
            } else {
                log.warn("Station setup failed");
            }
        }
        return super.appStart(app);
    }

    @Override
    public void appStop(IScope app) {
        log.info("Stopping app: {}", app.getName());
        // pull the station and close it
        Station station = stations.remove(app);
        if (station != null && station.isActive()) {
            station.stop();
        }
        // shutdown the executor
        executor.shutdownNow();
        super.appStop(app);
    }

    @Override
    public boolean roomStart(IScope room) {
        log.info("Starting room: {}", room.getName());
        // create a Station for the room on start
        if (!stations.containsKey(room)) {
            // create a new station
            Station station = new Station(room);
            // create the outgoing feed
            if (station.createFeed()) {
                log.info("Station instanced successfully");
                // register in the map
                stations.put(room, station);
            } else {
                log.warn("Station setup failed");
            }
        }
        return super.roomStart(room);
    }

    @Override
    public void roomStop(IScope room) {
        log.info("Stopping room: {}", room.getName());
        // pull the station and close it
        Station station = stations.remove(room);
        if (station != null && station.isActive()) {
            station.stop();
        }
        super.roomStop(room);
    }

    /**
     * A stream has started.
     * 
     * @param stream
     *            publishing stream
     */
    public void streamBroadcastStart(IBroadcastStream stream) {
        log.info("Starting stream: {}", stream.getPublishedName());
        // dont forget to call super!
        super.streamBroadcastStart(stream);
        // get the stream scope
        IScope scope = stream.getScope();
        // get the station
        Station station = stations.get(scope);
        // add the stream as a station feed
        station.addFeed(stream);
    }

    /**
     * A stream is closing.
     * 
     * @param stream
     *            published stream
     */
    @Override
    public void streamBroadcastClose(IBroadcastStream stream) {
        log.info("Closing stream: {}", stream.getPublishedName());
        // get the stream scope
        IScope scope = stream.getScope();
        // get the station
        Station station = stations.get(scope);
        // remove the stream as a station feed
        station.removeFeed(stream);
        // dont forget to call super!
        super.streamBroadcastClose(stream);
    }

    public static Future<?> submit(Runnable task) {
        return executor.submit(task);
    }

    /**
     * Returns the active feeds stream name.
     * 
     * @param scopePath
     *            scope path
     * @return active feed name or null if not found
     */
    public String getActiveFeedName(String scopePath) {
        log.info("getActiveFeedName on {}", scopePath);
        IScope stationScope = ScopeUtils.resolveScope(scope, scopePath);
        if (stationScope != null) {
            Station station = stations.get(stationScope);
            if (station != null) {
                return station.getActiveFeedName();
            }
        }
        return null;
    }

    /**
     * Switches the source of a stream from one to another.
     * 
     * <pre>
     * NetConnection.call(&quot;switchLiveStream&quot;, &quot;streamNameToSwitchTo&quot;);
     * </pre>
     * 
     * @param streamName
     *            stream name to switch to
     * @return true if switch is successful and false otherwise
     */
    public boolean switchLiveStream(String streamName) {
        log.info("switchLiveStream to {}", streamName);
        IConnection conn = Red5.getConnectionLocal();
        if (conn != null) {
            Station station = stations.get(conn.getScope());
            if (station != null) {
                return station.switchFeed(streamName);
            }
        }
        return false;
    }

    /**
     * Switches the source of a stream from one to another.
     * 
     * JSP example:
     * 
     * <pre>
     * ApplicationContext appCtx = (ApplicationContext) getServletContext().getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
     * Application app = (Application) appCtx.getBean(&quot;web.handler&quot;);
     * String status = null;
     * String scopePath = request.getParameter(&quot;scopePath&quot;) == null ? &quot;redsupport339&quot; : request.getParameter(&quot;scopePath&quot;);
     * String streamName = request.getParameter(&quot;streamName&quot;);
     * if (scopePath != null &amp;&amp; streamName != null) {
     *     if (app.switchLiveStream(scopePath, streamName)) {
     *         status = &quot;Stream switched&quot;;
     *     } else {
     *         status = &quot;Stream switch failed&quot;;
     *     }
     * }
     * String activeStreamName = app.getActiveFeedName(scopePath);
     * </pre>
     * 
     * @param scopePath
     *            scope path
     * @param streamName
     *            stream name to switch to
     * @return true if switch is successful and false otherwise
     */
    public boolean switchLiveStream(String scopePath, String streamName) {
        log.info("switchLiveStream to {} at {}", streamName, scopePath);
        IScope stationScope = ScopeUtils.resolveScope(scope, scopePath);
        if (stationScope != null) {
            Station station = stations.get(stationScope);
            if (station != null) {
                return station.switchFeed(streamName);
            }
        }
        return false;
    }

    /**
     * Returns the names of all the streams in the current scope.
     * 
     * @return list of stream names
     */
    public List<String> getLiveStreams() {
        log.info("Listing streams");
        @SuppressWarnings({ "unchecked", "rawtypes" })
        List<String> streams = new ArrayList(scope.getBasicScopeNames(ScopeType.BROADCAST));
        return streams;
    }

}