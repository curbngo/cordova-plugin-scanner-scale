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
    private ScannerScale scannerScale;

    private Double lastWeight = null;
    private String lastUnit = null;
    private JSONObject lastScaleDataJson = null;
    private volatile boolean listeningForWeight = false;

    public Star(ScannerScale scannerScale) {
        this.scannerScale = scannerScale;
    }

    private boolean isConnected() {
        return mScale != null;
    }

    public void isConnected(CallbackContext callbackContext){
        boolean isConnected = isConnected();
        callbackContext.success(isConnected ? 1 : 0);
    }

    public void initialize(CallbackContext callbackContext) {
        callbackContext.success();
    }

    public void startDiscovery(CallbackContext callbackContext) {
        Log.d(LOG_TAG, "discover");
        stopDiscovery();
        scannerScale.setDiscoveryCallback(callbackContext);
        scannerScale.cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                Log.d(LOG_TAG, "discover on UI thread");
                mStarDeviceManager = new StarDeviceManager(scannerScale.cordova.getActivity(), StarDeviceManager.InterfaceType.BluetoothLowEnergy);
                mStarDeviceManager.scanForScales(new StarDeviceManagerCallback() {
                    @Override
                    public void onDiscoverScale(@NonNull ConnectionInfo connectionInfo) {
                        JSONObject jsonInfo = getDeviceInfo(connectionInfo);
                        try {
                            jsonInfo.put("update_type", "discovery_update");
                        } catch (JSONException e) {
                            Log.e(LOG_TAG, e.getMessage(), e);
                        }
                        scannerScale.sendDiscoveryUpdate(jsonInfo, true);
                    }
                });
                PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
                pluginResult.setKeepCallback(true);
                callbackContext.sendPluginResult(pluginResult);
            }
        });
    }

    public void stopDiscovery() {
        Log.d(LOG_TAG, "stopDiscovery");
        scannerScale.clearDiscoveryCallback();
        if (mStarDeviceManager != null) {
            mStarDeviceManager.stopScan();
        }
    }

    public void stopDiscovery(CallbackContext callbackContext) {
        Log.d(LOG_TAG, "stopDiscovery with callback");
        scannerScale.clearDiscoveryCallback();
        if (mStarDeviceManager != null) {
            mStarDeviceManager.stopScan();
        }
        callbackContext.success();
    }

    public void connect(String id, final CallbackContext callbackContext) {
        Log.d(LOG_TAG, "connect");
        scannerScale.setCallback(callbackContext);
        scannerScale.cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                Log.d(LOG_TAG, "connect on UI thread");
                if (mScale != null) {
                    Log.d(LOG_TAG, "Scale already connected. Exiting.");
                    PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
                    pluginResult.setKeepCallback(true);
                    callbackContext.sendPluginResult(pluginResult);
                } else {
                    Log.d(LOG_TAG, "Starting device connection to "+id);
                    ConnectionInfo connectionInfo = new ConnectionInfo.Builder()
                            .setBleInfo(id)
                            .build();
                    StarDeviceManager mStarDeviceManager = new StarDeviceManager(scannerScale.cordova.getActivity());
                    mScale = mStarDeviceManager.createScale(connectionInfo);

                    mScale.connect(new ScaleCallback() {
                        @Override
                        public void onConnect(Scale scale, int status) {
                            Log.d(LOG_TAG, "ScaleCallback onConnect");
                            stopDiscovery();
                            sendConnectionUpdate(scale, status);
                            if (status == Scale.CONNECT_SUCCESS) {
                                mScale.updateOutputConditionSetting(ScaleOutputConditionSetting.ContinuousOutputAtStableTimes);
                            }
                            if(listeningForWeight && lastScaleDataJson != null) {
                                sendWeightUpdate(lastScaleDataJson);
                            }
                        }

                        @Override
                        public void onReadScaleData(Scale scale, ScaleData scaleData) {
                            JSONObject readScaleDataJson = getWeightInfo(scaleData);
                            Log.d(LOG_TAG, "ScaleCallback onReadScaleData " + readScaleDataJson.toString());
                            try {
                                readScaleDataJson.put("update_type", "weight_update");
                                if (readScaleDataJson.has("weight") && readScaleDataJson.has("unit")) {
                                    double newWeight = readScaleDataJson.getDouble("weight");
                                    String newUnit = readScaleDataJson.getString("unit");
                                    if (lastWeight == null || !lastWeight.equals(newWeight) || !newUnit.equals(lastUnit)) {
                                        lastWeight = newWeight;
                                        lastUnit = newUnit;
                                        lastScaleDataJson = readScaleDataJson;
                                        if (listeningForWeight) {
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

                        @Override
                        public void onDisconnect(Scale scale, int status) {
                            Log.d(LOG_TAG, "ScaleCallback onDisconnect");
                            mScale = null;
                            sendDisconnectionUpdate(scale, status);
                        }
                    });
                    PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
                    pluginResult.setKeepCallback(true);
                    callbackContext.sendPluginResult(pluginResult);
                }
            }
        });
    }

    public void disconnect(CallbackContext callbackContext) {
        Log.d(LOG_TAG, "disconnect");
        if (mScale != null) {
            mScale.disconnect();
        }
        callbackContext.success();
    }

    public void listen(CallbackContext callbackContext) {
        if (!isConnected()) {
            callbackContext.error("Not connected to any scale");
            return;
        }
        listeningForWeight = true;
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
        scannerScale.clearCallback();
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

    private void sendConnectionUpdate(Scale scale, int status) {
        Log.d(LOG_TAG, "sendConnectionUpdate");
        JSONObject result = new JSONObject();
        try {
            result.put("update_type", "connection_update");
            switch (status) {
                case Scale.CONNECT_SUCCESS:
                    result.put("status", "success");
                    break;

                case Scale.CONNECT_NOT_AVAILABLE:
                    result.put("status", "not_available");
                    break;

                case Scale.CONNECT_ALREADY_CONNECTED:
                    result.put("status", "already_connected");
                    break;

                case Scale.CONNECT_TIMEOUT:
                    result.put("status", "timeout");
                    break;

                case Scale.CONNECT_READ_WRITE_ERROR:
                    result.put("status", "read_write_error");
                    break;

                case Scale.CONNECT_NOT_SUPPORTED:
                    result.put("status", "not_supported");
                    break;

                case Scale.CONNECT_NOT_GRANTED_PERMISSION:
                    result.put("status", "not_granted_permission");
                    break;

                default:
                case Scale.CONNECT_UNEXPECTED_ERROR:
                    result.put("status", "unexpected_error");
                    break;
            }
        } catch (JSONException e) {
            Log.e(LOG_TAG, "JSONException occurred: " + e.getMessage(), e);
        }
        if (scannerScale != null) {
            scannerScale.handleConnectionUpdate(result);
        }
    }

    private void sendDisconnectionUpdate(Scale scale, int status) {
        Log.d(LOG_TAG, "sendDisconnectionUpdate");
        JSONObject result = new JSONObject();
        try {
            result.put("update_type", "disconnection_update");
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
            scannerScale.handleDisconnectionUpdate(result);
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
            scannerScale.handleWeightUpdate(weightJson);
        }
    }

    public void beep(int code, CallbackContext callbackContext) {
        callbackContext.success();
    }
}
