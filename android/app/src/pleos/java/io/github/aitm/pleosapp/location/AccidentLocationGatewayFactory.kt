package io.github.aitm.pleosapp.location

import android.content.Context

object AccidentLocationGatewayFactory {
    fun create(context: Context): AccidentLocationGateway = DeviceAccidentLocationGateway(
        context, ReportLocationGatewayFactory.create(context),
    )
}
