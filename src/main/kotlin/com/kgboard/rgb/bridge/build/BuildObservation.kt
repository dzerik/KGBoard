package com.kgboard.rgb.bridge.build

import com.kgboard.rgb.runtime.OperationOutcome

/** SDK identity remains inside the adapter/tracker and is never published in runtime state. */
sealed interface BuildObservation {
    data class Started(val id: Any) : BuildObservation
    data class Finished(val id: Any, val outcome: OperationOutcome) : BuildObservation
}
