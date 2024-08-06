package scannerscale.cordova;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.util.Log;

import scannerscale.cordova.Star;
import scannerscale.cordova.Zebra;
import scannerscale.cordova.ScannerScaleInterface;

public class ScannerScale extends CordovaPlugin {

    private static final String LOG_TAG = "ScannerScale";
    private ScannerScaleInterface scannerScale = null;

    private CallbackContext callback = null;
    private CallbackContext discoveryCallback = null;
    
    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.e(LOG_TAG, "execute called " + action);
        if (action.equals("init")) {
            init(args.getString(0), callbackContext);
            return true;
        }
        if (scannerScale == null) {
            if ("isConnected".equals(action)){
                callbackContext.success(0);
                return true;
            }
            callbackContext.success("init not called");
            return false;
        }
        if ("startDiscovery".equals(action)) {
            scannerScale.startDiscovery(callbackContext);
            return true;
        } else if ("stopDiscovery".equals(action)) {
            scannerScale.stopDiscovery(callbackContext);
            return true;
        } else if ("connect".equals(action)) {
            String id = args.getString(0);
            scannerScale.connect(id, callbackContext);
            return true;
        } else if ("disconnect".equals(action)) {
            scannerScale.disconnect(callbackContext);
            return true;
        } else if ("isConnected".equals(action)) {
            scannerScale.isConnected(callbackContext);
            return true;
        } else if ("listen".equals(action)) {
            scannerScale.listen(callbackContext);
            return true;
        } else if ("stopListening".equals(action)) {
            scannerScale.stopListening(callbackContext);
            return true;
        }
        return false;
    }

    private void init(String scaleType, CallbackContext callbackContext) {
        if ("Star".equals(scaleType)) {
            scannerScale = new Star(this); // Pass 'this' to provide ScannerScale instance
        } else if ("Zebra".equals(scaleType)) {
            scannerScale = new Zebra(this); // Pass 'this' to provide ScannerScale instance
        } else {
            callbackContext.error("Unsupported scanner type: " + scaleType);
            return;
        }
        scannerScale.initialize(callbackContext);
    }


    protected void setCallback(CallbackContext callbackContext) {
        this.callback = callbackContext;
    }

    protected void setDiscoveryCallback(CallbackContext callbackContext) {
        this.discoveryCallback = callbackContext;
    }

    protected void clearCallback() {
        this.callback = null;
    }

    protected void clearDiscoveryCallback() {
        this.discoveryCallback = null;
    }

    protected void sendDiscoveryUpdate(JSONObject connectionJson, boolean keepCallback) {
        Log.d(LOG_TAG, "sendDiscoveryUpdate");
        if (this.discoveryCallback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, connectionJson);
            result.setKeepCallback(keepCallback);
            this.discoveryCallback.sendPluginResult(result);
        }
    }

    protected void handleGeneralUpdate(JSONObject result) {
        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, result);
        pluginResult.setKeepCallback(true);
        sendUpdateToJavascript(pluginResult);
    }

    private void sendUpdateToJavascript(PluginResult pluginResult) {
        Log.d(LOG_TAG, "sendUpdate");
        if (this.callback != null) {
            this.callback.sendPluginResult(pluginResult);
        }
    }
}
