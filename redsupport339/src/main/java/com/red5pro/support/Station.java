package com.red5pro.support;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.red5.logging.Red5LoggerFactory;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IBroadcastStream;
import org.red5.server.api.stream.IStreamListener;
import org.red5.server.api.stream.IStreamPacket;
import org.red5.server.net.rtmp.event.AudioData;
import org.red5.server.net.rtmp.event.IRTMPEvent;
import org.red5.server.net.rtmp.event.Notify;
import org.red5.server.net.rtmp.event.VideoData;
import org.red5.server.net.rtmp.message.Constants;
import org.red5.server.stream.ClientBroadcastStream;
import org.red5.server.stream.IProviderService;
import org.slf4j.Logger;

/**
 * Provides a grouping of resources per scope. This is meant to represent a TV station with a single feed to viewers, but n source feeds on
 * the station side.
 * 
 * @author Paul Gregoire
 */
public class Station {

    private static Logger log = Red5LoggerFactory.getLogger(Station.class, "redsupport339");

    private final IScope scope;

    /**
     * Name of the outgoing stream from this station.
     */
    private final String outgoingStreamName;

    /**
     * All the source feeds.
     */
    private ConcurrentMap<String, IBroadcastStream> feeds = new ConcurrentHashMap<>();

    /**
     * Name of the currently active source feed.
     */
    private String selectedFeedName;

    /**
     * The outgoing feed streaming the content from the selected source feed.
     */
    private ClientBroadcastStream outgoingFeed;

    /**
     * Queue for outgoing data.
     */
    private ConcurrentLinkedQueue<IRTMPEvent> queue = new ConcurrentLinkedQueue<>();

    /**
     * Whether or not this station is active.
     */
    private AtomicBoolean active = new AtomicBoolean(false);

    /**
     * Future for the output feeder.
     */
    private Future<?> outgoingFeeder;

    /**
     * Future for the source feed listener.
     */
    private Future<?> sourceListener;

    /**
     * Instance a new station for the given scope. .
     * 
     * @param scope Station scope
     */
    public Station(IScope scope) {
        this(scope, scope.getName());
    }

    /**
     * Instance a new station for the given scope. .
     * 
     * @param scope Station scope
     * @param outgoingStreamName Name of the outgoing stream
     */
    public Station(IScope scope, String outgoingStreamName) {
        log.info("Created new station for scope: {} with outgoing stream: {}", scope.getName(), outgoingStreamName);
        this.scope = scope;
        this.outgoingStreamName = outgoingStreamName;
    }

    /**
     * Create the outgoing feed and start it.
     * 
     * @return true if registration and start-up are successful; false otherwise
     */
    public boolean createFeed() {
        log.info("Creating server stream");
        outgoingFeed = new ClientBroadcastStream();
        outgoingFeed.setStreamId(1);
        outgoingFeed.setPublishedName(outgoingStreamName);
        outgoingFeed.setScope(scope);
        // if re-set of cbs doesnt work for quick-start we'll connect a dummy connection
        // IStreamCapableConnection
        // register the stream
        IProviderService providerService = (IProviderService) scope.getContext().getBean(IProviderService.BEAN_NAME);
        if (providerService.registerBroadcastStream(scope, outgoingFeed.getPublishedName(), outgoingFeed)) {
            log.info("Stream: {} registered on scope: {}", outgoingStreamName, scope.getName());
            // set / re-set the stream on the scope
            //IBroadcastScope bsScope = scope.getBroadcastScope(outgoingStreamName);
            //bsScope.setClientBroadcastStream(outgoingFeed);
            // start feeding
            outgoingFeeder = Application.submit(new Runnable() {
    
                @Override
                public void run() {
                    // sleep the task when nothing is in the queue
                    try {
                        // start publishing
                        outgoingFeed.startPublishing();
                        do {
                            IRTMPEvent out = queue.poll();
                            if (out != null) {
                                outgoingFeed.dispatchEvent(out);
                            }
                            // sleep for a few ticks
                            Thread.sleep(10L);
                        } while (true);
                    } catch (InterruptedException e) {
                        // this will happen when cancelled or at executor shutdown
                    } catch (Exception e) {
                        log.warn("Exception in outgoing feeder task", e);
                    }
                    log.info("Stopping outgoing feeder for {}", outgoingStreamName);
                }

            });
            // set active flag
            active.set(true);
            // all is well
            return true;
        } else {
            log.warn("Stream registration failed");
        }
        return false;
    }

    /**
     * Adds a source stream feed.
     * 
     * @param stream publishing stream source
     */
    public void addFeed(IBroadcastStream stream) {
        final String streamName = stream.getPublishedName();
        // dont add our station outgoing stream to feeds
        if (!outgoingStreamName.equals(streamName)) {
            log.info("Add feed: {}", streamName);
            feeds.put(streamName, stream);
        }
        // switch to the first feed that comes if none are currently selected
        if (feeds.size() == 1 && selectedFeedName == null) {
            // send the switch null to get the stream started
            Application.submit(new Runnable() {

                public void run() {
                    // sleep a hundredth of a second
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException e) {
                    }
                    switchFeed(streamName);
                }

            });
        }
    }

    /**
     * Removes a source stream feed.
     * 
     * @param stream published stream source
     */
    public void removeFeed(IBroadcastStream stream) {
        String streamName = stream.getPublishedName();
        log.info("Remove feed: {}", streamName);
        // remove from feeds
        feeds.remove(streamName);
        // if its the currently active feed, remove it from publishing to outgoing
        if (selectedFeedName.equals(streamName)) {
            log.info("Feed specified for removal was currently active, removing it");
            // cancel the listener
            if (sourceListener.cancel(true)) {
                log.info("Source feed listener cancelled");
            }
        }
    }

    public String getOutgoingStreamName() {
        return outgoingStreamName;
    }

    public ClientBroadcastStream getOutgoingFeed() {
        return outgoingFeed;
    }

    public String getActiveFeedName() {
        return selectedFeedName;
    }

    /**
     * Switch the active source feed to the given source feed, by name.
     * 
     * @param feedName
     *            feed to switch to
     * @return true if switch succeeded and false otherwise
     */
    public boolean switchFeed(String feedName) {
        log.info("Switch source stream to {} from {}", feedName, selectedFeedName);
        // special stream name to clear the selected stream and not replace it
        if (feedName == null || "null".equals(feedName)) {
            // clear selected name
            this.selectedFeedName = null;
            // return true since we've cleared the stream
            return true;
        } else {
            // check for the requested feed and fail if not registered
            if (feeds.containsKey(feedName)) {
                this.selectedFeedName = feedName;
            } else {
                log.info("Requested source not found: {}", feedName);
                return false;
            }
        }
        // cancel the listener
        if (sourceListener != null && sourceListener.cancel(true)) {
            log.info("Source feed listener cancelled");
        }
        // create a stream listener to feed the outgoing stream queue and a task to feed the outgoing stream and lastly to remove the listener
        log.info("Starting listener for {}", selectedFeedName);
        final IBroadcastStream feed = feeds.get(selectedFeedName);
        if (feed != null) {
            // create the listener 
            final IStreamListener listener = new IStreamListener() {

                @Override
                public void packetReceived(IBroadcastStream incomming, IStreamPacket packet) {
                    switch (packet.getDataType()) {
                        case Constants.TYPE_AUDIO_DATA:
                            try {
                                AudioData in = (AudioData) packet;
                                AudioData out = new AudioData(in.getData(), true);
                                out.setHeader(in.getHeader().clone());
                                // queue it 
                                queue.add(out);
                            } catch (Exception e) {
                                log.warn("Exception handling audio packet", e);
                            }
                            break;
                        case Constants.TYPE_VIDEO_DATA:
                            try {
                                VideoData in = (VideoData) packet;
                                VideoData out = new VideoData(in.getData(), true);
                                out.setHeader(in.getHeader().clone());
                                // queue it 
                                queue.add(out);
                            } catch (Exception e) {
                                log.warn("Exception handling video packet", e);
                            }
                            break;
                        case Constants.TYPE_NOTIFY:
                        case Constants.TYPE_INVOKE:
                            try {
                                // queue it 
                                queue.add(((Notify) packet).duplicate());
                            } catch (Exception e) {
                                log.warn("Exception handling notify packet", e);
                            }
                            break;
                    }
                }

            };
            // attach a listener to the source feed
            feed.addStreamListener(listener);
            // create the task
            sourceListener = Application.submit(new Runnable() {
    
                @Override
                public void run() {
                    // sleep the task until we need to remove the listener
                    try {
                        do {
                            // sleep for a second
                            Thread.sleep(1000L);
                        } while (true);
                    } catch (InterruptedException e) {
                        // this will happen when cancelled or at executor shutdown
                    } catch (Exception e) {
                        log.warn("Exception in source listener task", e);
                    } finally {
                        // remove the listener
                        feed.removeStreamListener(listener);
                    }
                    log.info("Stopping listener for {}", feed.getPublishedName());
                }

            });
            return true;
        }
        return false;
    }

    public boolean isActive() {
        return active.get();
    }

    /**
     * Stop the station and close all the streams.
     */
    public void stop() {
        if (active.compareAndSet(true, false)) {
            log.info("Stopping station for scope: {}", scope.getName());
            // close it all down
            IProviderService providerService = (IProviderService) scope.getContext().getBean(IProviderService.BEAN_NAME);
            if (!feeds.isEmpty()) {
                for (IBroadcastStream feed : feeds.values()) {
                    providerService.unregisterBroadcastStream(scope, feed.getPublishedName());
                    feed.stop();
                }
            }
            if (outgoingFeed != null) {
                if (outgoingFeeder != null) {
                    outgoingFeeder.cancel(true);
                }
                providerService.unregisterBroadcastStream(scope, outgoingFeed.getPublishedName());
                outgoingFeed.stop();
            }
            if (!queue.isEmpty()) {
                queue.clear();
            }
        }
    }

}
