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
import java.text.*;

import java.sql.Connection

@Slf4j
class PlatformLicensingReportProvider extends AbstractReportProvider {
	Plugin plugin
	MorpheusContext morpheusContext

	PlatformLicensingReportProvider(Plugin plugin, MorpheusContext context) {
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
		'platform-licensing-report'
	}

	@Override
	String getName() {
		'Platform Licensing'
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
		getRenderer().renderTemplate("hbs/platformLicensing", model)
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
		List<GroovyRowResult> instances = []
		List<GroovyRowResult> kubeworkers = []
		List<GroovyRowResult> apps = []
		List<GroovyRowResult> taskExecutions = []
		List<GroovyRowResult> distributedWorkers = []
		Connection dbConnection
		Long totalWorkloads = 0
		Long discoveredWorkloads = 0
		Long managedWorkloads = 0
		Long iacWorkloads = 0
		Long kubernetesWorkers = 0
		Long xaasWorkloads = 0
		Long automationExecutions = 0

		try {
			// Create a read-only database connection
			dbConnection = morpheus.report.getReadOnlyDatabaseConnection().blockingGet()
			// Evaluate if a search filter or phrase has been defined
			results = new Sql(dbConnection).rows("SELECT * FROM compute_server INNER JOIN compute_server_type ON compute_server.compute_server_type_id=compute_server_type.id WHERE compute_server.compute_server_type_id in (select id from compute_server_type where managed = 0 and container_hypervisor = 0 and vm_hypervisor = 0 );")
			kubeworkers = new Sql(dbConnection).rows("SELECT * FROM compute_server INNER JOIN compute_server_type ON compute_server.compute_server_type_id=compute_server_type.id WHERE compute_server.compute_server_type_id in (select id from compute_server_type where node_type = 'kube-worker');")
			instances = new Sql(dbConnection).rows("SELECT instance.id, provision_type.code, instance.name, instance_type_layout.provision_type_id as provision_data, compute_zone.name as cloud_name, account.name as account_name FROM instance LEFT JOIN compute_zone on instance.provision_zone_id = compute_zone.id LEFT JOIN account ON instance.account_id = account.id LEFT JOIN instance_type_layout ON instance.layout_id = instance_type_layout.id LEFT JOIN provision_type ON instance_type_layout.provision_type_id = provision_type.id  order by id asc;")
			apps = new Sql(dbConnection).rows("SELECT * FROM app INNER JOIN app_template_type ON app.template_type_id=app_template_type.id WHERE app.template_type_id in (select id from app_template_type where code = 'terraform' OR code = 'arm' OR code = 'cloudFormation');")
			taskExecutions = new Sql(dbConnection).rows("SELECT * from job_execution WHERE created_by_id IS NOT NULL AND (start_date BETWEEN (CURDATE() - INTERVAL 365 DAY) AND CURDATE()) order by start_date desc;")
			distributedWorkers = new Sql(dbConnection).rows("SELECT * FROM distributed_worker;")
		} finally {
	    	// Close the database connection
			morpheus.report.releaseDatabaseConnection(dbConnection)
		}
		Observable<GroovyRowResult> observable = Observable.fromIterable(results) as Observable<GroovyRowResult>
		observable.map{ resultRow ->
			Map<String,Object> data = [id: resultRow.id]
			ReportResultRow resultRowRecord = new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: displayOrder++, dataMap: data)
			totalWorkloads++
			switch(resultRow.discovered) {
				case false:
					//log.info "Workload Name: ${resultRow.name} - Status: ${resultRow.discovered}"
					managedWorkloads++
					break;
				case true:
					//log.info "Workload Name: ${resultRow.name} - Status: ${resultRow.discovered}"
					discoveredWorkloads++
					break;
			}
			return resultRowRecord
		}.buffer(50).doOnComplete {
			morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.ready).blockingGet();
		}.doOnError { Throwable t ->
			morpheus.report.updateReportResultStatus(reportResult,ReportResult.Status.failed).blockingGet();
		}.subscribe {resultRows ->
			morpheus.report.appendResultRows(reportResult,resultRows).blockingGet()
		}

		instances.each { instance ->
			if (instance.code == "workflow"){
				xaasWorkloads++
			} else {
				managedWorkloads++
			}
		}

		Map<String,Object> data = [totalDiscoveredWorkloads: discoveredWorkloads, totalManagedWorkloads: managedWorkloads, totalIaCWorkloads: apps.size(), totalKubeWorkers: kubeworkers.size(), totalExecutions: taskExecutions.size(), totalXaaSInstances: xaasWorkloads]
		ReportResultRow resultRowRecord = new ReportResultRow(section: ReportResultRow.SECTION_HEADER, displayOrder: displayOrder++, dataMap: data)
        morpheus.report.appendResultRows(reportResult,[resultRowRecord]).blockingGet()
	}

	// https://developer.morpheusdata.com/api/com/morpheusdata/core/ReportProvider.html#method.summary
	// The description associated with the custom report
	 @Override
	 String getDescription() {
		 return "Platform Licensing"
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