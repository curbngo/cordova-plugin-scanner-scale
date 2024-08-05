package scannerscale.cordova;

import android.annotation.SuppressLint;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;
import android.graphics.Point;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Base64;
import android.view.ViewTreeObserver;
import java.io.ByteArrayOutputStream;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

import com.zebra.scannercontrol.FirmwareUpdateEvent;
import com.zebra.scannercontrol.BarCodeView;
import com.zebra.scannercontrol.DCSSDKDefs;
import com.zebra.scannercontrol.DCSScannerInfo;
import com.zebra.scannercontrol.SDKHandler;
import com.zebra.scannercontrol.IDcsSdkApiDelegate;
import com.zebra.barcode.sdk.sms.ConfigurationUpdateEvent;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import android.util.Xml;

public class Zebra implements ScannerScaleInterface, IDcsSdkApiDelegate {

    private static final String LOG_TAG = "Zebra";

    private SDKHandler sdkHandler;
    private int currentConnectedScannerID = -1;
    private HandlerThread beepHandlerThread;
    private Handler beepHandler;
    private HandlerThread weightHandlerThread;
    private Handler weightHandler;
    private Runnable weightRunnable;
    private Double lastWeight = null;
    private String lastUnit = null;
    private volatile boolean listeningForWeight = false;
    private static final double TOLERANCE = 0.0001;

    private FrameLayout barcodeDisplayArea;
    private RelativeLayout snapiLayout;

    private ArrayList<DCSScannerInfo> mSNAPIList = new ArrayList<>();
    private ArrayList<DCSScannerInfo> mScannerInfoList = new ArrayList<>();

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private ScannerScale scannerScale;
    DCSSDKDefs.DCSSDK_COMMAND_OPCODE opcode;

    public Zebra(ScannerScale scannerScale) {
        this.scannerScale = scannerScale;
        Log.d(LOG_TAG, "Zebra initialized with ScannerScale.");
    }

    private boolean isConnected() {
        return currentConnectedScannerID > -1;
    }

    public void isConnected(CallbackContext callbackContext) {
        boolean isConnected = isConnected();
        Log.d(LOG_TAG, "isConnected called. Result: " + isConnected);
        callbackContext.success(isConnected ? 1 : 0);
    }

    public void initialize(CallbackContext callbackContext) {
        Log.d(LOG_TAG, "initialize called.");
        scannerScale.cordova.getActivity().runOnUiThread(() -> {
            if (sdkHandler == null) {

                beepHandlerThread = new HandlerThread("BeepHandlerThread");
                beepHandlerThread.start();
                beepHandler = new Handler(beepHandlerThread.getLooper());

                // Initialize handlers for weight reading
                weightHandlerThread = new HandlerThread("WeightHandlerThread");
                weightHandlerThread.start();
                weightHandler = new Handler(weightHandlerThread.getLooper());

                sdkHandler = new SDKHandler(scannerScale.cordova.getActivity().getApplicationContext(), true);
                Log.d(LOG_TAG, "SDKHandler created.");
            }
            sdkHandler.dcssdkSetDelegate(Zebra.this);
            sdkHandler.dcssdkEnableAvailableScannersDetection(true);
            sdkHandler.dcssdkSetOperationalMode(DCSSDKDefs.DCSSDK_MODE.DCSSDK_OPMODE_SNAPI);

            int notificationsMask = DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_APPEARANCE.value |
                                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SCANNER_DISAPPEARANCE.value |
                                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_ESTABLISHMENT.value |
                                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_SESSION_TERMINATION.value |
                                    DCSSDKDefs.DCSSDK_EVENT.DCSSDK_EVENT_BARCODE.value;

            sdkHandler.dcssdkSubsribeForEvents(notificationsMask);
            Log.d(LOG_TAG, "SDKHandler subscribed for events with mask: " + notificationsMask);
            callbackContext.success();
        });
    }

    public void startDiscovery(CallbackContext callbackContext) {
        Log.d(LOG_TAG, "startDiscovery called. No operation needed.");
        callbackContext.success();
    }

    public void stopDiscovery(CallbackContext callbackContext) {
        Log.d(LOG_TAG, "stopDiscovery called. No operation needed.");
        callbackContext.success();
    }

    public void connect(String index, CallbackContext callbackContext) {
        Log.d(LOG_TAG, "connect called with index: " + index);
        connect(callbackContext);
    }

    public void listen(CallbackContext callbackContext) {

        if(!isConnected()){
            callbackContext.error("Not connected to any scale");
        }

        scannerScale.cordova.getActivity().runOnUiThread(() -> {

            if(!listeningForWeight){
                listeningForWeight = true;
                String inXml = "<inArgs><scannerID>" + currentConnectedScannerID + "</scannerID></inArgs>";
                startWeightPolling(inXml, DCSSDKDefs.DCSSDK_COMMAND_OPCODE.DCSSDK_READ_WEIGHT);
            }

        });
        callbackContext.success();
    }

    public void stopListening(CallbackContext callbackContext) {
        if(listeningForWeight){
            stopWeightPolling();
        }
        listeningForWeight = false;
        scannerScale.clearCallback();
        callbackContext.success();
    }

    public void connect(CallbackContext callbackContext) {
        Log.d(LOG_TAG, "connect method called.");
        if(isConnected() && listeningForWeight){
            stopWeightPolling();
        }
        scannerScale.setCallback(callbackContext);
        scannerScale.cordova.getActivity().runOnUiThread(() -> {
            mScannerInfoList.clear();
            mSNAPIList.clear();
            if(isConnected()){
                PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
                pluginResult.setKeepCallback(true);
                callbackContext.sendPluginResult(pluginResult);
                return;
            }
            sdkHandler.dcssdkGetAvailableScannersList(mScannerInfoList);

            for (DCSScannerInfo device : mScannerInfoList) {
                Log.d(LOG_TAG, "device Scanner Name: "+device.getConnectionType());
                if (device.getConnectionType() == DCSSDKDefs.DCSSDK_CONN_TYPES.DCSSDK_CONNTYPE_USB_SNAPI) {
                    mSNAPIList.add(device);
                    sendConnectionUpdate(device, device.isActive());
                }
            }

            if (!mSNAPIList.isEmpty()) {
                Log.d(LOG_TAG, "device list size: "+mSNAPIList.size());
                Log.d(LOG_TAG, "is active: "+mSNAPIList.get(0).isActive());
                if (mSNAPIList.size() == 1 && mSNAPIList.get(0).isActive()) {
                    currentConnectedScannerID = mSNAPIList.get(0).getScannerID();
                } else {
                    for (DCSScannerInfo scanner : mSNAPIList) {
                        if (!scanner.isActive()) {
                            Log.d(LOG_TAG, "Connecting to scanner ID: " + scanner.getScannerID());
                            connectToScanner(scanner);
                            break;
                        }
                    }
                }
            } else {
                getSnapiBarcode();
            }
            PluginResult pluginResult = new PluginResult(PluginResult.Status.NO_RESULT);
            pluginResult.setKeepCallback(true);
            callbackContext.sendPluginResult(pluginResult);
        });
    }

    private void connectToScanner(DCSScannerInfo scanner) {
        Log.d(LOG_TAG, "Connecting to scanner with ID: " + scanner.getScannerID());
        Callable<Boolean> callable = () -> {
            boolean result = sdkHandler.dcssdkEstablishCommunicationSession(scanner.getScannerID()) ==
                            DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS;
            if (result) {
                currentConnectedScannerID = scanner.getScannerID();
                Log.d(LOG_TAG, "Successfully connected to scanner ID: " + scanner.getScannerID());
            } else {
                Log.e(LOG_TAG, "Failed to connect to scanner ID: " + scanner.getScannerID());
            }
            return result;
        };

        Future<Boolean> future = executorService.submit(callable);
        try {
            boolean result = future.get();
            if (result) {
                sendConnectionUpdate(scanner, !scanner.isActive());
            } else {
                scannerScale.cordova.getActivity().runOnUiThread(() -> 
                    Toast.makeText(scannerScale.cordova.getActivity(), "Failed to connect to scanner", Toast.LENGTH_SHORT).show()
                );
            }
        } catch (InterruptedException | ExecutionException e) {
            Log.e(LOG_TAG, "Error connecting to scanner", e);
        }
    }

    private void getSnapiBarcode() {
        Log.d(LOG_TAG, "Getting SNAPI barcode.");

        scannerScale.cordova.getActivity().runOnUiThread(() -> {
            BarCodeView barCodeView = sdkHandler.dcssdkGetUSBSNAPIWithImagingBarcode();
            if (barCodeView == null) {
                Log.e(LOG_TAG, "Failed to retrieve BarCodeView.");
                return;
            }

            // Check if BarCodeView has valid dimensions
            if (barCodeView.getWidth() <= 0 || barCodeView.getHeight() <= 0) {
                // Use ViewTreeObserver to ensure BarCodeView is laid out properly
                barCodeView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        barCodeView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        String base64Image = convertBarCodeViewToBase64(barCodeView);
                        if (base64Image != null) {
                            JSONObject result = new JSONObject();
                            try {
                                result.put("update_type", "connection_update");
                                result.put("bitmap_url", "data:image/png;base64," + base64Image);
                            } catch (JSONException e) {
                                Log.e(LOG_TAG, "JSONException occurred: " + e.getMessage(), e);
                            }
                            scannerScale.handleConnectionUpdate(result);
                        }
                    }
                });
            } else {
                // BarCodeView is already laid out
                String base64Image = convertBarCodeViewToBase64(barCodeView);
                if (base64Image != null) {
                    JSONObject result = new JSONObject();
                    try {
                        result.put("update_type", "connection_update");
                        result.put("bitmap_url", "data:image/png;base64," + base64Image);
                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "JSONException occurred: " + e.getMessage(), e);
                    }
                    scannerScale.handleConnectionUpdate(result);
                }
            }
        });
    }

    private String convertBarCodeViewToBase64(BarCodeView barCodeView) {
        Log.d(LOG_TAG, "Converting BarCodeView to Base64.");

        // Create a bitmap from BarCodeView
        int width = barCodeView.getWidth();
        int height = barCodeView.getHeight();

        if (width <= 0 || height <= 0) {
            Log.e(LOG_TAG, "Invalid BarCodeView dimensions: width=" + width + ", height=" + height);
            return null;
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        barCodeView.draw(canvas);

        // Convert bitmap to Base64
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            byte[] byteArray = baos.toByteArray();
            return Base64.encodeToString(byteArray, Base64.DEFAULT);
        } catch (IOException e) {
            Log.e(LOG_TAG, "IOException occurred while converting bitmap to Base64: " + e.getMessage(), e);
            return null;
        }
    }
    
    private double getDeviceScreenSize() {
        Log.d(LOG_TAG, "Calculating device screen size.");
        double screenInches = 0;
        WindowManager windowManager = scannerScale.cordova.getActivity().getWindowManager();
        Display display = windowManager.getDefaultDisplay();

        int mWidthPixels;
        int mHeightPixels;

        try {
            Point realSize = new Point();
            Display.class.getMethod("getRealSize", Point.class).invoke(display, realSize);
            mWidthPixels = realSize.x;
            mHeightPixels = realSize.y;
            DisplayMetrics dm = new DisplayMetrics();
            display.getMetrics(dm);
            double x = Math.pow(mWidthPixels / dm.xdpi, 2);
            double y = Math.pow(mHeightPixels / dm.ydpi, 2);
            screenInches = Math.sqrt(x + y);
            Log.d(LOG_TAG, "Device screen size calculated: " + screenInches + " inches.");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error calculating screen size", e);
        }
        return screenInches;
    }

    @Override
    public void disconnect(CallbackContext callbackContext) {
        Log.d(LOG_TAG, "disconnect called.");
        if (sdkHandler != null) {
            sdkHandler.dcssdkTerminateCommunicationSession(currentConnectedScannerID);
            currentConnectedScannerID = -1;
            listeningForWeight = false;
            scannerScale.clearCallback();
            Log.d(LOG_TAG, "Disconnected from scanner.");
        }
        callbackContext.success();
    }

    private void startWeightPolling(final String inXml, final DCSSDKDefs.DCSSDK_COMMAND_OPCODE opcode) {
        weightRunnable = new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    boolean result;
                    StringBuilder sbOutXml = new StringBuilder();
                    result = executeCommand(opcode, inXml, sbOutXml, currentConnectedScannerID);

                    if (opcode == DCSSDKDefs.DCSSDK_COMMAND_OPCODE.DCSSDK_READ_WEIGHT && result) {
                        try {
                            JSONObject readScaleDataJson = getWeightInfo(sbOutXml.toString());
                            Log.d(LOG_TAG, "readScaleDataJson IS: "+readScaleDataJson.toString());
                            readScaleDataJson.put("update_type", "weight_update");

                            if (readScaleDataJson.has("weight") && readScaleDataJson.has("unit")) {
                                Double newWeight = readScaleDataJson.getDouble("weight");
                                String newUnit = readScaleDataJson.getString("unit");

                                boolean weightChanged = lastWeight == null || Math.abs(lastWeight - newWeight) > TOLERANCE;
                                boolean unitChanged = !newUnit.equals(lastUnit);

                                
                                if (weightChanged || unitChanged) {
                                    Log.d(LOG_TAG, "newWeight IS: "+newWeight.toString());
                                    if(lastWeight != null){
                                        Log.d(LOG_TAG, "lastWeight IS: "+lastWeight.toString());
                                    }
                                    Log.d(LOG_TAG, "newUnit IS: "+newUnit);
                                    if(lastUnit != null){
                                        Log.d(LOG_TAG, "lastUnit IS: "+lastUnit);
                                    }
                                    lastWeight = newWeight;
                                    lastUnit = newUnit;
                                    sendWeightUpdate(readScaleDataJson);
                                } else {
                                    Log.d(LOG_TAG, "No change in weight");
                                }
                            }
                        } catch (JSONException e) {
                            Log.e(LOG_TAG, "JSONException occurred: " + e.getMessage(), e);
                        }

                        // Polling interval
                        weightHandler.postDelayed(this, 1000); // 1 second
                    }
                }).start();
            }
        };

        // Start polling
        weightHandler.post(weightRunnable);
    }

    private void stopWeightPolling() {
        if (weightRunnable != null) {
            weightHandler.removeCallbacks(weightRunnable);
            listeningForWeight = false;
        }
    }

    private String getScaleStatus(int status) {
        switch (status) {
            case 1: // Scale not enabled
            case 2: // Scale not ready
                return "INVALID"; // or "Invalid value" depending on how you want to interpret these statuses

            case 3: // Stable weight over limit
            case 4: // Stable weight under zero
            case 6: // Stable zero weight
            case 7: // Stable non-zero weight
                return "STABLE"; // All stable weight conditions

            case 5: // Non-stable weight
                return "UNSTABLE"; // Non-stable weight condition

            default:
                return "ERROR"; // Unknown status or error condition
        }
    }

    private boolean executeCommand(DCSSDKDefs.DCSSDK_COMMAND_OPCODE opCode, String inXml, StringBuilder outXml, int scannerId) {
        Log.d(LOG_TAG, "executeCommand called with opcode: " + opCode + ", XML: " + inXml + ", ScannerID: " + scannerId);
        if (sdkHandler != null) {
            DCSSDKDefs.DCSSDK_RESULT result = sdkHandler.dcssdkExecuteCommandOpCodeInXMLForScanner(opCode, inXml, outXml, scannerId);
            boolean success = result == DCSSDKDefs.DCSSDK_RESULT.DCSSDK_RESULT_SUCCESS;
            Log.d(LOG_TAG, "Command executed. Result: " + result + ", Success: " + success);
            return success;
        }
        Log.e(LOG_TAG, "SDKHandler is null.");
        return false;
    }

    private JSONObject getWeightInfo(String xmlData) {
        JSONObject obj = new JSONObject();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(xmlData));
            int event = parser.getEventType();
            String elementName = null;

            while (event != XmlPullParser.END_DOCUMENT) {
                switch (event) {
                    case XmlPullParser.START_TAG:
                        elementName = parser.getName();
                        break;
                    case XmlPullParser.TEXT:
                        String elementValue = parser.getText();
                            if (elementName != null) {
                                if ("weight_mode".equals(elementName)) {
                                    obj.put("unit", "English".equals(elementValue.trim()) ? "lb" : "kg");
                                } else if ("weight".equals(elementName)) {
                                    // Parse weight as a Double
                                    try {
                                        double weight = Double.parseDouble(elementValue.trim());
                                        obj.put("weight", weight);
                                    } catch (NumberFormatException e) {
                                        Log.e(LOG_TAG, "Error parsing weight value: " + elementValue, e);
                                    }
                                } else if ("status".equals(elementName)) {
                                    obj.put("status", getScaleStatus(Integer.parseInt(elementValue.trim())));
                                } else {
                                    obj.put(elementName, elementValue.trim());
                                }
                            }
                            break;
                    case XmlPullParser.END_TAG:
                        elementName = null;
                        break;
                }
                event = parser.next();
            }
        } catch (IOException | XmlPullParserException | JSONException e) {
            Log.e(LOG_TAG, "Error processing weight data: " + e.getMessage(), e);
        }
        return obj;
    }

    private void sendWeightUpdate(JSONObject weightJson) {
        Log.d(LOG_TAG, "sendWeightUpdate called with JSON: " + weightJson.toString());
        if (scannerScale != null) {
            scannerScale.handleWeightUpdate(weightJson);
        }
    }

    private void sendConnectionUpdate(DCSScannerInfo scannerInfo, boolean status) {
        Log.d(LOG_TAG, "sendConnectionUpdate called with scannerInfo: " + scannerInfo.toString() + ", Status: " + status);
        JSONObject result = new JSONObject();
        try {
            result.put("update_type", "connection_update");
            result.put("connection_type", scannerInfo.getConnectionType());
            result.put("serial_number", scannerInfo.getScannerHWSerialNumber());
            result.put("scanner_id", scannerInfo.getScannerID());
            result.put("model", scannerInfo.getScannerModel());
            result.put("name", scannerInfo.getScannerName());
            result.put("is_active", scannerInfo.isActive());
            result.put("status", "success");
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Error creating connection update JSON", e);
        }
        if (scannerScale != null) {
            scannerScale.handleConnectionUpdate(result);
        }
    }

    public void beep(int code, CallbackContext callbackContext) {
        if (!isConnected()) {
            callbackContext.error("Not connected to any scale");
            return;
        }

        final String inXml = "<inArgs><scannerID>" + currentConnectedScannerID + "</scannerID><cmdArgs><arg-int>" + code + "</arg-int></cmdArgs></inArgs>";
        final DCSSDKDefs.DCSSDK_COMMAND_OPCODE opc = DCSSDKDefs.DCSSDK_COMMAND_OPCODE.DCSSDK_SET_ACTION;

        Runnable beepRunnable = new Runnable() {
            @Override
            public void run() {
                boolean result;
                StringBuilder sbOutXml = new StringBuilder();
                result = executeCommand(opc, inXml, sbOutXml, currentConnectedScannerID);
                if (result) {
                    Log.d(LOG_TAG, "Beeped " + sbOutXml.toString());
                    callbackContext.success("Beeped");
                } else {
                    callbackContext.error("Failed to beep");
                }
            }
        };
        beepHandler.post(beepRunnable);
    }

    @Override
    public void dcssdkEventScannerAppeared(DCSScannerInfo availableScanner) {
        Log.d(LOG_TAG, "dcssdkEventScannerAppeared called with scannerInfo: " + availableScanner.toString());
        mScannerInfoList.add(availableScanner);
    }

    @Override
    public void dcssdkEventScannerDisappeared(int scannerID) {
        Log.d(LOG_TAG, "dcssdkEventScannerDisappeared called with scannerID: " + scannerID);
        mScannerInfoList.removeIf(scanner -> scanner.getScannerID() == scannerID);
    }

    @Override
    public void dcssdkEventCommunicationSessionEstablished(DCSScannerInfo activeScanner) {
        Log.d(LOG_TAG, "dcssdkEventCommunicationSessionEstablished called with scannerInfo: " + activeScanner.toString());
        currentConnectedScannerID = activeScanner.getScannerID();
        updateUIOnSessionEstablished();
    }

    @Override
    public void dcssdkEventCommunicationSessionTerminated(int scannerID) {
        Log.d(LOG_TAG, "dcssdkEventCommunicationSessionTerminated called with scannerID: " + scannerID);
        if (currentConnectedScannerID == scannerID) {
            currentConnectedScannerID = -1;
        }
        updateUIOnSessionTerminated();
    }

    @Override
    public void dcssdkEventBarcode(byte[] barcodeData, int barcodeType, int fromScannerID) {
        Log.d(LOG_TAG, "dcssdkEventBarcode");
        JSONObject result = new JSONObject();
        try {
            result.put("update_type", "barcode_scan");
            result.put("type", getBarcodeTypeName(barcodeType));
            result.put("barcode", new String(barcodeData));
        } catch (JSONException e) {
            Log.e(LOG_TAG, "JSONException occurred: " + e.getMessage(), e);
        }
        sendBarcodeScan(result);
    }

    @Override
    public void dcssdkEventImage(byte[] imageData, int fromScannerID) {
        Log.d(LOG_TAG, "dcssdkEventImage called with fromScannerID: " + fromScannerID);
        // Handle image event if required
    }

    @Override
    public void dcssdkEventVideo(byte[] videoData, int fromScannerID) {
        Log.d(LOG_TAG, "dcssdkEventVideo called with fromScannerID: " + fromScannerID);
        // Handle video event if required
    }

    @Override
    public void dcssdkEventFirmwareUpdate(FirmwareUpdateEvent firmwareUpdateEvent) {
        Log.d(LOG_TAG, "dcssdkEventFirmwareUpdate called with event: " + firmwareUpdateEvent.toString());
        // Handle firmware update event if required
    }

    @Override
    public void dcssdkEventAuxScannerAppeared(DCSScannerInfo newTopology, DCSScannerInfo auxScanner) {
        Log.d(LOG_TAG, "dcssdkEventAuxScannerAppeared called with newTopology: " + newTopology.toString() + ", auxScanner: " + auxScanner.toString());
        // Handle auxiliary scanner appearance event if required
    }

    @Override
    public void dcssdkEventConfigurationUpdate(ConfigurationUpdateEvent configUpdateEvent) {
        Log.d(LOG_TAG, "dcssdkEventConfigurationUpdate called with event: " + configUpdateEvent.toString());
        // Handle configuration update event if required
    }

    @Override
    public void dcssdkEventBinaryData(byte[] binaryData, int fromScannerID) {
        Log.d(LOG_TAG, "dcssdkEventBinaryData called with fromScannerID: " + fromScannerID);
        // Handle binary data event if required
    }

    private void updateUIOnSessionEstablished() {
        Log.d(LOG_TAG, "updateUIOnSessionEstablished called.");
        scannerScale.cordova.getActivity().runOnUiThread(() -> {
            // Update UI for session establishment
        });
    }

    private void updateUIOnSessionTerminated() {
        Log.d(LOG_TAG, "updateUIOnSessionTerminated called.");
        scannerScale.cordova.getActivity().runOnUiThread(() -> {
            // Update UI for session termination
        });
    }

    private void sendBarcodeScan(JSONObject barcodeJson) {
        Log.d(LOG_TAG, "sendBarcodeScan called");
        if (scannerScale != null) {
            scannerScale.handleBarcodeScan(barcodeJson);
        }
    }

    private static String getBarcodeTypeName(int code) {
        switch (code) {
            case 1:
                return "Code 39";
            case 2:
                return "Codabar";
            case 3:
                return "Code 128";
            case 4:
                return "Discrete (Standard) 2 of 5";
            case 5:
                return "IATA";
            case 6:
                return "Interleaved 2 of 5";
            case 7:
                return "Code 93";
            case 8:
                return "UPC-A";
            case 9:
                return "UPC-E0";
            case 10:
                return "EAN-8";
            case 11:
                return "EAN-13";
            case 12:
                return "Code 11";
            case 13:
                return "Code 49";
            case 14:
                return "MSI";
            case 15:
                return "EAN-128";
            case 16:
                return "UPC-E1";
            case 17:
                return "PDF-417";
            case 18:
                return "Code 16K";
            case 19:
                return "Code 39 Full ASCII";
            case 20:
                return "UPC-D";
            case 21:
                return "Code 39 Trioptic";
            case 22:
                return "Bookland";
            case 23:
                return "Coupon Code";
            case 24:
                return "NW-7";
            case 25:
                return "ISBT-128";
            case 26:
                return "Micro PDF";
            case 27:
                return "DataMatrix";
            case 28:
                return "QR Code";
            case 29:
                return "Micro PDF CCA";
            case 30:
                return "PostNet US";
            case 31:
                return "Planet Code";
            case 32:
                return "Code 32";
            case 33:
                return "ISBT-128 Con";
            case 34:
                return "Japan Postal";
            case 35:
                return "Australian Postal";
            case 36:
                return "Dutch Postal";
            case 37:
                return "MaxiCode";
            case 38:
                return "Canadian Postal";
            case 39:
                return "UK Postal";
            case 40:
                return "Macro PDF";
            case 44:
                return "Micro QR code";
            case 45:
                return "Aztec";
            case 48:
                return "GS1 Databar (RSS-14)";
            case 49:
                return "RSS Limited";
            case 50:
                return "GS1 Databar Expanded (RSS Expanded)";
            case 55:
                return "Scanlet";
            case 72:
                return "UPC-A + 2 Supplemental";
            case 73:
                return "UPC-E0 + 2 Supplemental";
            case 74:
                return "EAN-8 + 2 Supplemental";
            case 75:
                return "EAN-13 + 2 Supplemental";
            case 80:
                return "UPC-E1 + 2 Supplemental";
            case 81:
                return "CCA EAN-128";
            case 82:
                return "CCA EAN-13";
            case 83:
                return "CCA EAN-8";
            case 84:
                return "CCA RSS Expanded";
            case 85:
                return "CCA RSS Limited";
            case 86:
                return "CCA RSS-14";
            case 87:
                return "CCA UPC-A";
            case 88:
                return "CCA UPC-E";
            case 89:
                return "CCC EAN-128";
            case 90:
                return "TLC-39";
            case 97:
                return "CCB EAN-128";
            case 98:
                return "CCB EAN-13";
            case 99:
                return "CCB EAN-8";
            case 100:
                return "CCB RSS Expanded";
            case 101:
                return "CCB RSS Limited";
            case 102:
                return "CCB RSS-14";
            case 103:
                return "CCB UPC-A";
            case 104:
                return "CCB UPC-E";
            case 105:
                return "Signature Capture";
            case 113:
                return "Matrix 2 of 5";
            case 114:
                return "Chinese 2 of 5";
            case 136:
                return "UPC-A + 5 Supplemental";
            case 137:
                return "UPC-E0 + 5 Supplemental";
            case 138:
                return "EAN-8 + 5 Supplemental";
            case 139:
                return "EAN-13 + 5 Supplemental";
            case 144:
                return "UPC-E1 + 5 Supplemental";
            case 154:
                return "Macro Micro PDF";
            default:
                return "Unknown barcode type";
        }
    }
}
