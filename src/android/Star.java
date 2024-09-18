package scannerscale.cordova;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.starmicronics.starmgsio.ConnectionInfo;
import com.starmicronics.starmgsio.Scale;
import com.starmicronics.starmgsio.ScaleCallback;
import com.starmicronics.starmgsio.ScaleData;
import com.starmicronics.starmgsio.ScaleOutputConditionSetting;
import com.starmicronics.starmgsio.StarDeviceManager;
import com.starmicronics.starmgsio.StarDeviceManagerCallback;

import scannerscale.cordova.ScannerScale;

public class Star implements ScannerScaleInterface {

    private static final String LOG_TAG = "Star";

    private StarDeviceManager mStarDeviceManager;
    private Scale mScale;
    private boolean mScaleIsConnected;
    private ScannerScale scannerScale;

    private Double lastWeight = null;
    private String lastUnit = null;
    private JSONObject lastScaleDataJson = null;
    private volatile boolean listeningForWeight = false;
    private static final int CONNECTION_TIMEOUT_MS = 10000;

    public Star(ScannerScale scannerScale) {
        this.scannerScale = scannerScale;
        Log.d(LOG_TAG, "Star instance created: " + this);
    }

    public boolean isConnected() {
        return mScale != null && mScaleIsConnected;
    }

    public boolean isConnected(CallbackContext callbackContext){
        return isConnected();
    }
    
    public void initialize(CallbackContext callbackContext) {}

    public void startDiscovery(CallbackContext callbackContext) {
        Log.d(LOG_TAG, "discover");
        stopDiscovery();
        scannerScale.cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                Log.d(LOG_TAG, "discover on UI thread");
                if (mStarDeviceManager == null) {
                    mStarDeviceManager = new StarDeviceManager(scannerScale.cordova.getActivity(), StarDeviceManager.InterfaceType.BluetoothLowEnergy);
                }
                mStarDeviceManager.scanForScales(new StarDeviceManagerCallback() {
                    @Override
                    public void onDiscoverScale(@NonNull ConnectionInfo connectionInfo) {
                        JSONObject jsonInfo = getDeviceInfo(connectionInfo);
                        try {
                            jsonInfo.put("update_type", "discovery_update");
                        } catch (JSONException e) {
                            Log.e(LOG_TAG, e.getMessage(), e);
                        }
                        scannerScale.broadcastEvent(jsonInfo);
                    }
                });
                callbackContext.success();
            }
        });
    }

    public void stopDiscovery() {
        Log.d(LOG_TAG, "stopDiscovery");
        if (mStarDeviceManager != null) {
            mStarDeviceManager.stopScan();
        }
    }

    public void stopDiscovery(CallbackContext callbackContext) {
        Log.d(LOG_TAG, "stopDiscovery with callback");
        if (mStarDeviceManager != null) {
            mStarDeviceManager.stopScan();
        }
        callbackContext.success();
    }

    public void connect(final String id, final CallbackContext callbackContext) {
        Log.d(LOG_TAG, "connect");
        lastWeight = null;
        lastUnit = null;
        lastScaleDataJson = null;
        scannerScale.cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                Log.d(LOG_TAG, "connect on UI thread");
                if (isConnected()) {
                    Log.d(LOG_TAG, "Scale already connected");
                    callbackContext.success();
                } else {
                    Log.d(LOG_TAG, "Starting device connection to " + id);
                    ConnectionInfo connectionInfo = new ConnectionInfo.Builder()
                            .setBleInfo(id)
                            .build();

                    if (mStarDeviceManager == null) {
                        mStarDeviceManager = new StarDeviceManager(scannerScale.cordova.getActivity(), StarDeviceManager.InterfaceType.BluetoothLowEnergy);
                    }
                    mScale = mStarDeviceManager.createScale(connectionInfo);

                    final Handler timeoutHandler = new Handler();
                    final Runnable timeoutRunnable = new Runnable() {
                        @Override
                        public void run() {
                            if (!mScaleIsConnected) {
                                Log.d(LOG_TAG, "Connection timeout. Failing callback.");
                                callbackContext.error("Connection timeout.");
                            }
                        }
                    };

                    timeoutHandler.postDelayed(timeoutRunnable, CONNECTION_TIMEOUT_MS);

                    mScaleIsConnected = false;
                    mScale.connect(new ScaleCallback() {
                        @Override
                        public void onConnect(Scale scale, int status) {
                            Log.d(LOG_TAG, "ScaleCallback onConnect");
                            timeoutHandler.removeCallbacks(timeoutRunnable);
                            stopDiscovery();
                            if (status == Scale.CONNECT_SUCCESS) {
                                mScaleIsConnected = true;
                                mScale.updateOutputConditionSetting(ScaleOutputConditionSetting.ContinuousOutputAtStableTimes);
                                Log.d(LOG_TAG, "Connection successful, sending success callback.");
                                callbackContext.success();
                            } else {
                                callbackContext.error("Connection failed with status: " + getConnectionStatus(status));
                            }
                        }

                        @Override
                        public void onReadScaleData(Scale scale, ScaleData scaleData) {
                            handleScaleData(scaleData);
                        }

                        @Override
                        public void onDisconnect(Scale scale, int status) {
                            Log.d(LOG_TAG, "ScaleCallback onDisconnect");
                            mScale = null;
                            mScaleIsConnected = false;
                            sendDisconnectionUpdate(scale, status);
                        }
                    });
                }
            }
        });
    }

    private void handleScaleData(ScaleData scaleData) {
        JSONObject readScaleDataJson = getWeightInfo(scaleData);
        Log.d(LOG_TAG, "ScaleCallback onReadScaleData " + readScaleDataJson.toString());
        try {
            if (readScaleDataJson.has("weight") && readScaleDataJson.has("unit")) {
                double newWeight = readScaleDataJson.getDouble("weight");
                String newUnit = readScaleDataJson.getString("unit");

                if (lastWeight == null || !lastWeight.equals(newWeight) || !newUnit.equals(lastUnit)) {
                    lastWeight = newWeight;
                    lastUnit = newUnit;
                    lastScaleDataJson = readScaleDataJson;

                    if (listeningForWeight || newWeight == 0.0) {
                        sendWeightUpdate(readScaleDataJson);
                        Log.d(LOG_TAG, "listening for weight, sending update");
                    } else {
                        Log.d(LOG_TAG, "not listening for weight");
                    }
                } else {
                    Log.d(LOG_TAG, "no change in weight " + readScaleDataJson.toString());
                }
            }
        } catch (JSONException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
        }
    }

    public void disconnect(CallbackContext callbackContext) {
        Log.d(LOG_TAG, "disconnect");
        if (isConnected()) {
            mScale.disconnect(); 
        }
        mScaleIsConnected = false;
        callbackContext.success();
    }

    public void listen(CallbackContext callbackContext) {
        if (!isConnected()) {
            callbackContext.error("Not connected to any scale");
            return;
        }
        listeningForWeight = true;
        Log.d(LOG_TAG, "starting to listen" + lastScaleDataJson.toString());
        if (lastScaleDataJson != null) {
            Log.d(LOG_TAG, " sending cached update " + lastScaleDataJson.toString());
            sendWeightUpdate(lastScaleDataJson);
        } else {
            Log.d(LOG_TAG, "No cached update");
        }
        callbackContext.success();
    }

    public void stopListening(CallbackContext callbackContext) {
        listeningForWeight = false;
        callbackContext.success();
    }

    private JSONObject getDeviceInfo(@NonNull ConnectionInfo connectionInfo) {
        Log.d(LOG_TAG, "getDeviceInfo");
        JSONObject obj = new JSONObject();
        try {
            obj.put("interface", connectionInfo.getInterfaceType().name());
            obj.put("name", connectionInfo.getDeviceName());
            obj.put("id", connectionInfo.getIdentifier());
            obj.put("type", connectionInfo.getScaleType().name());
        } catch (JSONException e) {
            Log.e(LOG_TAG, e.getMessage(), e);
        }
        return obj;
    }

    private String getConnectionStatus(int status) {
        switch (status) {
            case Scale.CONNECT_SUCCESS:
                return "success";

            case Scale.CONNECT_NOT_AVAILABLE:
                return "not_available";

            case Scale.CONNECT_ALREADY_CONNECTED:
                return "already_connected";

            case Scale.CONNECT_TIMEOUT:
                return "timeout";

            case Scale.CONNECT_READ_WRITE_ERROR:
                return "read_write_error";

            case Scale.CONNECT_NOT_SUPPORTED:
                return "not_supported";

            case Scale.CONNECT_NOT_GRANTED_PERMISSION:
                return "not_granted_permission";

            default:
                return "unexpected_error";
        }
    }

    private void sendDisconnectionUpdate(Scale scale, int status) {
        Log.d(LOG_TAG, "sendDisconnectionUpdate");
        JSONObject result = new JSONObject();
        try {
            result.put("update_type", "disconnection");
            switch (status) {
                case Scale.DISCONNECT_SUCCESS:
                    result.put("status", "success");
                    break;

                case Scale.DISCONNECT_NOT_CONNECTED:
                    result.put("status", "not_connected");
                    break;

                case Scale.DISCONNECT_TIMEOUT:
                    result.put("status", "timeout");
                    break;

                case Scale.DISCONNECT_READ_WRITE_ERROR:
                    result.put("status", "read_write_error");
                    break;

                case Scale.DISCONNECT_UNEXPECTED_ERROR:
                    result.put("status", "unexpected_error");
                    break;

                case Scale.DISCONNECT_UNEXPECTED_DISCONNECTION:
                    result.put("status", "unexpected_disconnection");
                    break;
            }
        } catch (JSONException e) {
            Log.e(LOG_TAG, "JSONException occurred: " + e.getMessage(), e);
        }
        if (scannerScale != null) {
            scannerScale.broadcastEvent(result);
        }
    }

    private JSONObject getWeightInfo(ScaleData scaleData) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("weight", scaleData.getWeight());
            obj.put("unit", scaleData.getUnit().toString());
            obj.put("comparatorResult", scaleData.getComparatorResult());
            obj.put("dataType", scaleData.getDataType());
            obj.put("status", scaleData.getStatus());
            obj.put("numberOfDecimalPlaces", scaleData.getNumberOfDecimalPlaces());
            obj.put("rawString", scaleData.getRawString());
        } catch (JSONException e) {
            Log.e(LOG_TAG, "JSONException occurred: " + e.getMessage(), e);
        }
        return obj;
    }

    private void sendWeightUpdate(JSONObject weightJson) {
        if (scannerScale != null) {
            try {
                weightJson.put("update_type", "weight_update");
            } catch (JSONException e) {
                Log.e(LOG_TAG, "JSONException occurred: " + e.getMessage(), e);
                return;
            }
            scannerScale.broadcastEvent(weightJson);
        }
    }

    public void beep(int code, CallbackContext callbackContext) {
        callbackContext.success();
    }
}
