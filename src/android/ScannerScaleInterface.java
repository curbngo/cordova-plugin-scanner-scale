package scannerscale.cordova;

import org.apache.cordova.CallbackContext;

public interface ScannerScaleInterface {
    void initialize(CallbackContext callbackContext);
    void startDiscovery(CallbackContext callbackContext);
    void stopDiscovery(CallbackContext callbackContext);
    void connect(String id, CallbackContext callbackContext);
    void disconnect(CallbackContext callbackContext);
    void isConnected(CallbackContext callbackContext);
    void listen(CallbackContext callbackContext);
    void stopListening(CallbackContext callbackContext);
    void beep(int code, CallbackContext callbackContext);
}