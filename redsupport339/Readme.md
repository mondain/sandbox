#Many In But Only One Out
This example implements a "Station" style application where several publishers stream to a station, but only one selected 
stream gets pushed out to subscribers. Each red5 room/scope will have a `Station` instance once it starts.

Once you're up-and-running, open your browser to the switcher page
`http://localhost:5080/redsupport339/switcher.jsp`

In the form, enter your scope name and the stream name you want to switch to. For top-level application scope, leave the scope name blank (this part might fail). The example screen shots show the use of a station / room / scope named `myroom`.


You may also switch the streams from within a connected Flash client via [`NetConnection.call`](http://help.adobe.com/en_US/FlashPlatform/reference/actionscript/3/flash/net/NetConnection.html#call())
```javascript
nc.call("switchLiveStream", null, "streamNameToSwitchTo");
```

## Screen Shots
Below are screenshots of the app in-action

IMAGES HERE

---
This app started life as a support ticket over at [Infrared5](http://infrared5.com) for a [Red5 Pro](http://red5pro.com/) customer.
