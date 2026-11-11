package com.arc.nupcar.vehicle

import ai.pleos.playground.vehicle.Vehicle

interface VehicleSession {
    val vehicle: Vehicle

    fun requireInitialized()
}
