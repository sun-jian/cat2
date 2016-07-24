package org.unidal.cat.plugin.transaction.page;

import java.io.IOException;
import java.util.Date;

import javax.servlet.ServletException;

import org.unidal.cat.plugin.transaction.TransactionConstants;
import org.unidal.cat.plugin.transaction.filter.TransactionAllNameFilter;
import org.unidal.cat.plugin.transaction.filter.TransactionAllNameGraphFilter;
import org.unidal.cat.plugin.transaction.filter.TransactionAllTypeFilter;
import org.unidal.cat.plugin.transaction.filter.TransactionAllTypeGraphFilter;
import org.unidal.cat.plugin.transaction.filter.TransactionNameFilter;
import org.unidal.cat.plugin.transaction.filter.TransactionNameGraphFilter;
import org.unidal.cat.plugin.transaction.filter.TransactionTypeFilter;
import org.unidal.cat.plugin.transaction.filter.TransactionTypeGraphFilter;
import org.unidal.cat.plugin.transaction.model.entity.TransactionName;
import org.unidal.cat.plugin.transaction.model.entity.TransactionReport;
import org.unidal.cat.plugin.transaction.model.entity.TransactionType;
import org.unidal.cat.plugin.transaction.page.transform.AllReportDistributionBuilder;
import org.unidal.cat.plugin.transaction.page.transform.DistributionDetailVisitor;
import org.unidal.cat.plugin.transaction.page.transform.PieGraphChartVisitor;
import org.unidal.cat.plugin.transaction.view.GraphPayload.AverageTimePayload;
import org.unidal.cat.plugin.transaction.view.GraphPayload.DurationPayload;
import org.unidal.cat.plugin.transaction.view.GraphPayload.FailurePayload;
import org.unidal.cat.plugin.transaction.view.GraphPayload.HitPayload;
import org.unidal.cat.plugin.transaction.view.NameViewModel;
import org.unidal.cat.plugin.transaction.view.TypeViewModel;
import org.unidal.cat.plugin.transaction.view.svg.GraphBuilder;
import org.unidal.cat.spi.ReportManager;
import org.unidal.cat.spi.ReportPeriod;
import org.unidal.lookup.annotation.Inject;
import org.unidal.lookup.util.StringUtils;
import org.unidal.web.mvc.PageHandler;
import org.unidal.web.mvc.annotation.InboundActionMeta;
import org.unidal.web.mvc.annotation.OutboundActionMeta;
import org.unidal.web.mvc.annotation.PayloadMeta;

import com.dianping.cat.Constants;
import com.dianping.cat.mvc.PayloadNormalizer;
import com.dianping.cat.report.ReportPage;

public class Handler implements PageHandler<Context> {
	@Inject
	private GraphBuilder m_builder;

	@Inject
	private HistoryGraphs m_historyGraph;

	@Inject
	private JspViewer m_jspViewer;

	@Inject
	private PayloadNormalizer m_normalizer;

	@Inject
	private AllReportDistributionBuilder m_distributionBuilder;

	@Inject(TransactionConstants.NAME)
	private ReportManager<TransactionReport> m_manager;

	private void buildDistributionInfo(Model model, String type, String name, TransactionReport report) {
		PieGraphChartVisitor chartVisitor = new PieGraphChartVisitor(type, name);
		DistributionDetailVisitor detailVisitor = new DistributionDetailVisitor(type, name);

		chartVisitor.visitTransactionReport(report);
		detailVisitor.visitTransactionReport(report);
		model.setDistributionChart(chartVisitor.getPieChart().getJsonString());
		model.setDistributionDetails(detailVisitor.getDetails());
	}

	private void buildAllReportDistributionInfo(Model model, String type, String name, String ip,
	      TransactionReport report) {
		m_distributionBuilder.buildAllReportDistributionInfo(model, type, name, ip, report);
	}

	private void buildTransactionMetaInfo(Model model, Payload payload, TransactionReport report) {
		String type = payload.getType();
		String sortBy = payload.getSortBy();
		String query = payload.getQueryName();
		String ip = payload.getIpAddress();

		if (!StringUtils.isEmpty(type)) {
			NameViewModel table = new NameViewModel(report, ip, type, query, sortBy);
			
			model.setTable(table);
			model.setPieChart(table.getPieChart());
		} else {
			model.setTable(new TypeViewModel(report, ip, query, sortBy));
		}
	}

	private void buildTransactionNameGraph(Model model, TransactionReport report, String type, String name, String ip) {
		if (name == null || name.length() == 0) {
			name = Constants.ALL;
		}

		TransactionType t = report.findOrCreateMachine(ip).findOrCreateType(type);
		TransactionName transactionName = t.findOrCreateName(name);

		if (transactionName != null) {
			String graph1 = m_builder.build(new DurationPayload("Duration Distribution", "Duration (ms)", "Count",
			      transactionName));
			String graph2 = m_builder.build(new HitPayload("Hits Over Time", "Time (min)", "Count", transactionName));
			String graph3 = m_builder.build(new AverageTimePayload("Average Duration Over Time", "Time (min)",
			      "Average Duration (ms)", transactionName));
			String graph4 = m_builder.build(new FailurePayload("Failures Over Time", "Time (min)", "Count",
			      transactionName));

			model.setGraph1(graph1);
			model.setGraph2(graph2);
			model.setGraph3(graph3);
			model.setGraph4(graph4);
		}
	}

	private void handleHistoryGraph(Model model, Payload payload) throws IOException {
		String filterId;
		if (payload.getDomain().equals(Constants.ALL)) {
			filterId = payload.getName() == null ? TransactionAllTypeGraphFilter.ID : TransactionAllNameGraphFilter.ID;
		} else {
			filterId = payload.getName() == null ? TransactionTypeGraphFilter.ID : TransactionNameGraphFilter.ID;
		}

		ReportPeriod period = payload.getReportPeriod();
		String domain = payload.getDomain();
		Date date = payload.getStartTime();
		TransactionReport current = m_manager.getReport(period, period.getStartTime(date), domain, filterId, //
		      "ip", payload.getIpAddress(), //
		      "type", payload.getType(), //
		      "name", payload.getName());
		TransactionReport last = m_manager.getReport(period, period.getLastStartTime(date), domain, filterId, //
		      "ip", payload.getIpAddress(), //
		      "type", payload.getType(), //
		      "name", payload.getName());
		TransactionReport baseline = m_manager.getReport(period, period.getBaselineStartTime(date), domain, filterId, //
		      "ip", payload.getIpAddress(), //
		      "type", payload.getType(), //
		      "name", payload.getName());

		model.setReport(current);

		if (current != null) {
			String type = payload.getType();
			String name = payload.getName();
			String ip = payload.getIpAddress();

			if (Constants.ALL.equalsIgnoreCase(ip)) {
				buildDistributionInfo(model, type, name, current);
			} else if (Constants.ALL.equals(payload.getDomain())) {
				buildAllReportDistributionInfo(model, type, name, ip, current);
			}
		}

		m_historyGraph.buildTrend(model, current, last, baseline);
		// m_historyGraph.buildTrendGraph(model, payload);
	}

	private void handleHistoryReport(Model model, Payload payload) throws IOException {
		String filterId;
		if (payload.getDomain().equals(Constants.ALL)) {
			filterId = payload.getType() == null ? TransactionAllTypeFilter.ID : TransactionAllNameFilter.ID;
		} else {
			filterId = payload.getType() == null ? TransactionTypeFilter.ID : TransactionNameFilter.ID;
		}

		ReportPeriod period = payload.getReportPeriod();
		Date startTime = payload.getStartTime();
		TransactionReport report = m_manager.getReport(period, startTime, payload.getDomain(), filterId, //
		      "ip", payload.getIpAddress(), //
		      "type", payload.getType());

		if (report != null) {
			buildTransactionMetaInfo(model, payload, report);
		}

		model.setReport(report);
	}

	private void handleHourlyGraph(Model model, Payload payload) throws IOException {
		String filterId;
		if (payload.getDomain().equals(Constants.ALL)) {
			filterId = payload.getName() == null ? TransactionAllTypeGraphFilter.ID : TransactionAllNameGraphFilter.ID;
		} else {
			filterId = payload.getName() == null ? TransactionTypeGraphFilter.ID : TransactionNameGraphFilter.ID;
		}

		Date startTime = payload.getStartTime();
		TransactionReport report = m_manager.getReport(ReportPeriod.HOUR, startTime, payload.getDomain(), filterId, //
		      "ip", payload.getIpAddress(), //
		      "type", payload.getType(), //
		      "name", payload.getName());

		if (report != null) {
			String type = payload.getType();
			String name = payload.getName();
			String ip = payload.getIpAddress();

			if (Constants.ALL.equalsIgnoreCase(ip)) {
				buildDistributionInfo(model, type, name, report);
			} else if (Constants.ALL.equals(payload.getDomain())) {
				buildAllReportDistributionInfo(model, type, name, ip, report);
			}

			buildTransactionNameGraph(model, report, type, name, ip);
		}

		model.setReport(report);
	}

	private void handleHourlyReport(Model model, Payload payload) throws IOException {
		String filterId;

		if (payload.getDomain().equals(Constants.ALL)) {
			filterId = payload.getType() == null ? TransactionAllTypeFilter.ID : TransactionAllNameFilter.ID;
		} else {
			filterId = payload.getType() == null ? TransactionTypeFilter.ID : TransactionNameFilter.ID;
		}

		Date startTime = payload.getStartTime();
		TransactionReport report = m_manager.getReport(ReportPeriod.HOUR, startTime, payload.getDomain(), filterId, //
		      "ip", payload.getIpAddress(), //
		      "type", payload.getType());

		if (report != null) {
			buildTransactionMetaInfo(model, payload, report);
		} else {
			report = new TransactionReport(payload.getDomain());
			report.setPeriod(ReportPeriod.HOUR);
			report.setStartTime(startTime);
		}

		model.setReport(report);
	}

	@Override
	@PayloadMeta(Payload.class)
	@InboundActionMeta(name = "t")
	public void handleInbound(Context ctx) throws ServletException, IOException {
		// display only, no action here
	}

	@Override
	@OutboundActionMeta(name = "t")
	public void handleOutbound(Context ctx) throws ServletException, IOException {
		Model model = new Model(ctx);
		Payload payload = ctx.getPayload();
		Action action = payload.getAction();

		normalizePayload(model, payload);

		switch (action) {
		case REPORT:
			if (payload.getReportPeriod() == ReportPeriod.HOUR) {
				handleHourlyReport(model, payload);
			} else {
				handleHistoryReport(model, payload);
			}

			break;
		case GRAPH:
			if (payload.getReportPeriod() == ReportPeriod.HOUR) {
				handleHourlyGraph(model, payload);
			} else {
				handleHistoryGraph(model, payload);
			}

			break;
		}

		TransactionReport report = model.getReport();

		if (report != null) {
			Date startTime = report.getStartTime();
			Date endTime = report.getPeriod().getNextStartTime(startTime);

			report.setEndTime(new Date(endTime.getTime() - 1000));
		}

		if (!ctx.isProcessStopped()) {
			m_jspViewer.view(ctx, model);
		}
	}

	private void normalizePayload(Model model, Payload payload) {
		m_normalizer.normalize(model, payload);

		model.setPage(ReportPage.TRANSACTION);
		model.setAction(payload.getAction());
		model.setQueryName(payload.getQueryName());
	}
}
