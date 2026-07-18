package com.shiraka.locatiobprovid.data.model

data class SavedLocation(
    val name: String, 
    val lat: Double, 
    val lng: Double,
    val wifiJson: String = "{\"isConnected\":false,\"connectedWifi\":null,\"nearbyWifi\":[]}",
    val cellJson: String = "[]"
)
