package com.morpheusdata.platforminsights

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportType
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.response.ServiceResponse
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j
import io.reactivex.Observable;
import java.util.Date
import java.util.concurrent.TimeUnit
import groovy.json.*

import java.sql.Connection

@Slf4j
class PlatformUsageReportProvider extends AbstractReportProvider {
	Plugin plugin
	MorpheusContext morpheusContext

	PlatformUsageReportProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() {
		morpheusContext
	}

	@Override
	Plugin getPlugin() {
		plugin
	}

	@Override
	String getCode() {
		'platform-usage-report'
	}

	@Override
	String getName() {
		'Platform Usage'
	}

	ServiceResponse validateOptions(Map opts) {
		return ServiceResponse.success()
	}


	@Override
	HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
		ViewModel<String> model = new ViewModel<String>()
		def HashMap<String, String> reportPayload = new HashMap<String, String>();
		def webnonce = morpheus.getWebRequest().getNonceToken()
		reportPayload.put("webnonce",webnonce)
		reportPayload.put("reportdata",reportRowsBySection)
		model.object = reportPayload
		getRenderer().renderTemplate("hbs/platformUsage", model)
	}


	@Override
	ContentSecurityPolicy getContentSecurityPolicy() {
		def csp = new ContentSecurityPolicy()
		csp
	}


	void process(ReportResult reportResult) {
		// Update the status of the report (generating) - https://developer.morpheusdata.com/api/com/morpheusdata/model/ReportResult.Status.html
		morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.generating).blockingGet();
		Long displayOrder = 0
		List<GroovyRowResult> results = []
		List<GroovyRowResult> loadbalancers = []
		Connection dbConnection
		try {
			// Create a read-only database connection
			dbConnection = morpheus.report.getReadOnlyDatabaseConnection().blockingGet()
			// Evaluate if a search filter or phrase has been defined
			results = new Sql(dbConnection).rows("SELECT account_integration.name,account_integration.is_plugin, account_integration.enabled, morpheus.account_integration_type.category AS integration_category, account_integration.account_id, morpheus.account_integration_type.code AS integration_name FROM morpheus.account_integration LEFT JOIN account_integration_type ON account_integration.integration_type_id = account_integration_type.id order by account_integration_type.code asc;")
            loadbalancers = new Sql(dbConnection).rows("SELECT network_load_balancer.name, network_load_balancer.description, network_load_balancer.type_id, network_load_balancer.enabled, network_load_balancer.zone_id, morpheus.network_load_balancer_type.code as lb_code FROM morpheus.network_load_balancer LEFT JOIN network_load_balancer_type ON network_load_balancer.type_id = network_load_balancer_type.id;")
	    // Close the database connection
		} finally {
			morpheus.report.releaseDatabaseConnection(dbConnection)
		}
		Observable<GroovyRowResult> observable = Observable.fromIterable(results) as Observable<GroovyRowResult>
		observable.map{ resultRow ->
			log.info("Mapping resultRow ${resultRow}")
			println "Mapping resultRow ${resultRow}"
			Map<String,Object> data = [name: resultRow.name, plugin: resultRow.is_plugin, enabled: resultRow.enabled, integration: resultRow.integration_name, category: resultRow.integration_category]
			ReportResultRow resultRowRecord = new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: displayOrder++, dataMap: data)
			log.info("resultRowRecord: ${resultRowRecord.dump()}")
			return resultRowRecord
		}.buffer(50).doOnComplete {
			morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.ready).blockingGet();
		}.doOnError { Throwable t ->
			morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.failed).blockingGet();
		}.subscribe {resultRows ->
			morpheus.report.appendResultRows(reportResult,resultRows).blockingGet()
		}
	}

	// https://developer.morpheusdata.com/api/com/morpheusdata/core/ReportProvider.html#method.summary
	// The description associated with the custom report
	 @Override
	 String getDescription() {
		 return "Platform Usage"
	 }

	// The category of the custom report
	 @Override
	 String getCategory() {
		 return 'Platform Insights'
	 }

	 @Override
	 Boolean getOwnerOnly() {
		 return false
	 }

	 @Override
	 Boolean getMasterOnly() {
		 return true
	 }

	 @Override
	 Boolean getSupportsAllZoneTypes() {
		 return true
	 }

	// https://developer.morpheusdata.com/api/com/morpheusdata/model/OptionType.html
	 @Override
	 List<OptionType> getOptionTypes() {}
 }