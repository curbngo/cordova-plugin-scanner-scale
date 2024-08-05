var ScannerScale = {

    // Initialize the scale plugin
    init: function(scaleType, successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'ScannerScale', 'init', [scaleType]);
    },

    // Start discovery of available scales
    startDiscovery: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'ScannerScale', 'startDiscovery', []);
    },

    // Stop discovery of scales
    stopDiscovery: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'ScannerScale', 'stopDiscovery', []);
    },

    // Connect to a specific scale by its ID
    connect: function(scaleId, successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'ScannerScale', 'connect', [scaleId]);
    },

    // Disconnect from the connected scale
    disconnect: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'ScannerScale', 'disconnect', []);
    },

    // Check if the scale is currently connected
    isConnected: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'ScannerScale', 'isConnected', []);
    },

    // Listen for live updates from the scale
    listen: function(updateCallback, errorCallback) {
        cordova.exec(updateCallback, errorCallback, 'ScannerScale', 'listen', []);
    },

    // Stop listening for live updates from the scale
    stopListening: function(successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'ScannerScale', 'stopListening', []);
    },

    // Make the scale beep
    beep: function(code, successCallback, errorCallback) {
        cordova.exec(successCallback, errorCallback, 'ScannerScale', 'beep', [code]);
    }
};

module.exports = ScannerScale;
