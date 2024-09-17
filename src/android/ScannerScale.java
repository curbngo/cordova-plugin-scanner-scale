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
            callbackContext.success(scannerScale.isConnected() ? 1 : 0);
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
        if (
            scannerScale == null || 
            (scannerScale instanceof Star && !"Star".equals(scaleType)) || 
            (scannerScale instanceof Zebra && !"Zebra".equals(scaleType))
        ) {
            if ("Star".equals(scaleType)) {
                scannerScale = new Star(this);
            } else if ("Zebra".equals(scaleType)) {
                scannerScale = new Zebra(this);
            } else {
                callbackContext.error("Unsupported scanner type: " + scaleType);
                return;
            }
        }

        // Initialize the scannerScale
        scannerScale.initialize(callbackContext);
        this.callback = callbackContext;

        // Send a success result to resolve the JavaScript promise
        PluginResult successResult = new PluginResult(PluginResult.Status.OK, "Initialization successful");
        successResult.setKeepCallback(true); // Keep the callback active
        callbackContext.sendPluginResult(successResult);
    }
    
    protected void clearCallback() {
        this.callback = null;
    }

    protected void broadcastEvent(JSONObject result) {
        if (this.callback != null) {
            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, result);
            pluginResult.setKeepCallback(true);
            this.callback.sendPluginResult(pluginResult);
        }
    }
}
