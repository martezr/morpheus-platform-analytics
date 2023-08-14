package com.morpheusdata.platforminsights

import com.morpheusdata.core.Plugin
import com.morpheusdata.views.HandlebarsRenderer
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.Dispatcher


class PlatformInsightsPlugin extends Plugin {

	@Override
	String getCode() {
		return 'platform-insights'
	}

	@Override
	void initialize() {

		// Morpheus Platform Licensing Report
		PlatformLicensingReportProvider platformLicensingReportProvider = new PlatformLicensingReportProvider(this, morpheus)
		this.pluginProviders.put(platformLicensingReportProvider.code, platformLicensingReportProvider)

		// Morpheus Platform Usage Report
		PlatformUsageReportProvider platformUsageReportProvider = new PlatformUsageReportProvider(this, morpheus)
		this.pluginProviders.put(platformUsageReportProvider.code, platformUsageReportProvider)

		this.setName("Platform Insights")
		this.setDescription("Morpheus platform insights plugin")
	}

	@Override
	void onDestroy() {
	}
}