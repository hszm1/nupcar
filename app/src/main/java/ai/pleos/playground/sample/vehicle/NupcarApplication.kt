package ai.pleos.playground.sample.vehicle

import ai.pleos.playground.sample.vehicle.session.GeneratedRestAudio
import ai.pleos.playground.sample.vehicle.session.PleosVehicleGateway
import ai.pleos.playground.sample.vehicle.session.RestSessionController
import ai.pleos.playground.sample.vehicle.session.SimulatedVehicleGateway
import ai.pleos.playground.sample.vehicle.session.VehicleGateway
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
