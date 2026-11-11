package com.arc.nupcar

import com.arc.nupcar.session.GeneratedRestAudio
import com.arc.nupcar.session.PleosVehicleGateway
import com.arc.nupcar.session.RestSessionController
import com.arc.nupcar.session.SimulatedVehicleGateway
import com.arc.nupcar.session.VehicleGateway
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NupcarApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val controller: RestSessionController by lazy {
        RestSessionController(::createVehicle, GeneratedRestAudio(), scope)
    }

    private fun createVehicle(): VehicleGateway =
        if (BuildConfig.SIMULATED_VEHICLE) SimulatedVehicleGateway() else PleosVehicleGateway(this)
}
