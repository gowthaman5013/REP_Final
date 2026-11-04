import React, { useState } from 'react';
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Cell
} from 'recharts';
import API from '../../shared/services/api';
import LoadingSpinner from '../../shared/components/LoadingSpinner';

export default function WaterPredictionCard() {
  const [prediction, setPrediction] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const fetchPrediction = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await API.get('/resident/prediction/next-month');
      setPrediction(res.data);
    } catch (err) {
      console.error('Failed to load prediction:', err);
      setError(
        err.response?.data?.message ||
        'Unable to generate water consumption prediction at this time.'
      );
    } finally {
      setLoading(false);
    }
  };

  // Format currency
  const formatCurrency = (val) => {
    if (val == null) return '₹0.00';
    return `₹${Number(val).toLocaleString('en-IN', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`;
  };

  return (
    <div className="card glass-card border shadow-sm p-4 mb-4 bg-white rounded-4">
      {/* Header */}
      <div className="d-flex align-items-center justify-content-between mb-3 flex-wrap gap-2">
        <div className="d-flex align-items-center gap-2">
          <div
            className="d-flex align-items-center justify-content-center rounded-3 shadow-xs"
            style={{ width: 42, height: 42, backgroundColor: '#EFF6FF', color: '#2563EB' }}
          >
            <i className="bi bi-stars fs-4"></i>
          </div>
          <div>
            <div className="d-flex align-items-center gap-2">
              <h5 className="fw-bold mb-0 text-dark">
                AI Next-Month Water & Bill Prediction
              </h5>
              <span className="badge bg-primary-subtle text-primary border border-primary-subtle px-2 py-1 fs-8 fw-semibold rounded-pill">
                Random Forest ML
              </span>
              {prediction?.isNewResident && (
                <span className="badge bg-info-subtle text-info border border-info-subtle px-2 py-1 fs-8 fw-semibold rounded-pill d-flex align-items-center gap-1">
                  <i className="bi bi-house-door"></i> Initial Baseline
                </span>
              )}
            </div>
            <p className="text-muted fs-7 mb-0">
              Predictive consumption telemetry & estimated tariff billing
            </p>
          </div>
        </div>

        <div className="d-flex align-items-center gap-2">
          {prediction ? (
            <span className="badge bg-light text-muted border px-2.5 py-1.5 fs-8">
              Target: <strong className="text-dark">{prediction.predictionMonthLabel}</strong>
            </span>
          ) : (
            <button
              className="btn btn-primary btn-sm rounded-pill px-3 py-1.5 fw-semibold d-flex align-items-center gap-1.5 shadow-sm"
              onClick={fetchPrediction}
              disabled={loading}
            >
              {loading ? (
                <>
                  <span className="spinner-border spinner-border-sm me-1" role="status"></span>
                  Predicting...
                </>
              ) : (
                <>
                  <i className="bi bi-lightning-charge-fill"></i>
                  Predict Now
                </>
              )}
            </button>
          )}
        </div>
      </div>

      {/* Initial state before user clicks Predict Now */}
      {!prediction && !loading && !error && (
        <div className="py-4 text-center border rounded-3 bg-light-subtle my-2">
          <div
            className="d-inline-flex align-items-center justify-content-center rounded-circle mb-3 shadow-xs"
            style={{ width: 52, height: 52, backgroundColor: '#EFF6FF', color: '#2563EB' }}
          >
            <i className="bi bi-graph-up-arrow fs-3"></i>
          </div>
          <h6 className="fw-bold text-dark mb-1">
            Forecast Next Month's Water & Bill
          </h6>
          <p className="text-muted fs-7 mb-3 mx-auto" style={{ maxWidth: 500 }}>
            Click below to calculate your next month's projected water usage and estimated bill amount using our trained Random Forest regression model.
          </p>
          <button
            className="btn btn-primary rounded-pill px-4 py-2 fw-semibold d-inline-flex align-items-center gap-2 shadow-sm"
            onClick={fetchPrediction}
          >
            <i className="bi bi-lightning-charge-fill"></i>
            Predict Now
          </button>
        </div>
      )}

      {/* Loading state during prediction */}
      {loading && (
        <div className="py-5 text-center">
          <LoadingSpinner />
          <p className="text-muted fs-7 mt-2 mb-0">
            Running Random Forest regression on household consumption patterns...
          </p>
        </div>
      )}

      {/* Error State */}
      {error && !loading && (
        <div className="alert alert-warning border border-warning-subtle rounded-3 p-3 mb-0 d-flex align-items-center justify-content-between">
          <div className="d-flex align-items-center gap-2">
            <i className="bi bi-exclamation-triangle-fill text-warning fs-5"></i>
            <span className="fs-7 text-dark">{error}</span>
          </div>
          <button className="btn btn-sm btn-outline-dark rounded-pill px-3" onClick={fetchPrediction}>
            Predict Now
          </button>
        </div>
      )}

      {/* Prediction Content */}
      {prediction && !loading && (
        <>
          {/* Key Metric Cards */}
          <div className="row g-3 mb-4">
            {/* Predicted Water Consumption */}
            <div className="col-md-6 col-lg-3">
              <div className="p-3 rounded-3 border bg-light h-100 position-relative overflow-hidden">
                <div className="d-flex align-items-center justify-content-between mb-2">
                  <small className="text-muted fw-semibold text-uppercase fs-8">
                    Predicted Usage
                  </small>
                  <i className="bi bi-droplet-half text-primary fs-5"></i>
                </div>
                <h3 className="fw-extrabold text-dark mb-0">
                  {prediction.predictedConsumptionKl.toFixed(2)}{' '}
                  <span className="fs-6 fw-semibold text-muted">kL</span>
                </h3>
                <small className="text-muted fs-8 d-block mt-1">
                  ≈ {Math.round(prediction.predictedConsumptionLitres).toLocaleString()} Litres
                  {prediction.isNewResident && ` • ${prediction.householdSize || 2} Occupants`}
                </small>
              </div>
            </div>

            {/* Estimated Bill Amount */}
            <div className="col-md-6 col-lg-3">
              <div className="p-3 rounded-3 border bg-light h-100 position-relative overflow-hidden">
                <div className="d-flex align-items-center justify-content-between mb-2">
                  <small className="text-muted fw-semibold text-uppercase fs-8">
                    Estimated Bill
                  </small>
                  <i className="bi bi-cash-stack text-success fs-5"></i>
                </div>
                <h3 className="fw-extrabold text-dark mb-0">
                  {formatCurrency(prediction.estimatedAmount)}
                </h3>
                <small className="text-muted fs-8 d-block mt-1">
                  {prediction.isNewResident ? 'Baseline 3-Tier Tariff' : 'Tiers + Fixed Charges'}
                </small>
              </div>
            </div>

            {/* Previous Month Usage */}
            <div className="col-md-6 col-lg-3">
              <div className="p-3 rounded-3 border bg-light h-100 position-relative overflow-hidden">
                <div className="d-flex align-items-center justify-content-between mb-2">
                  <small className="text-muted fw-semibold text-uppercase fs-8">
                    Last Month Actual
                  </small>
                  <i className={`bi ${prediction.isNewResident || prediction.previousMonthConsumptionKl == null ? 'bi-calendar-check text-muted' : 'bi-clock-history text-secondary'} fs-5`}></i>
                </div>
                {prediction.isNewResident || prediction.previousMonthConsumptionKl == null ? (
                  <>
                    <h3 className="fw-extrabold text-muted mb-0">
                      N/A
                    </h3>
                    <small className="text-muted fs-8 d-block mt-1">
                      New Resident • 1st Month
                    </small>
                  </>
                ) : (
                  <>
                    <h3 className="fw-extrabold text-dark mb-0">
                      {prediction.previousMonthConsumptionKl?.toFixed(2) || '0.00'}{' '}
                      <span className="fs-6 fw-semibold text-muted">kL</span>
                    </h3>
                    <small className="text-muted fs-8 d-block mt-1">
                      Previous bill cycle
                    </small>
                  </>
                )}
              </div>
            </div>

            {/* Consumption Trend Delta */}
            <div className="col-md-6 col-lg-3">
              <div className="p-3 rounded-3 border bg-light h-100 position-relative overflow-hidden">
                <div className="d-flex align-items-center justify-content-between mb-2">
                  <small className="text-muted fw-semibold text-uppercase fs-8">
                    Forecast Trend
                  </small>
                  <i
                    className={`bi ${
                      prediction.isNewResident || prediction.trendDirection === 'INITIAL_BASELINE'
                        ? 'bi-info-circle text-primary'
                        : prediction.trendDirection === 'DECREASE'
                        ? 'bi-arrow-down-right-circle text-success'
                        : prediction.trendDirection === 'INCREASE'
                        ? 'bi-arrow-up-right-circle text-danger'
                        : 'bi-dash-circle text-info'
                    } fs-5`}
                  ></i>
                </div>
                {prediction.isNewResident || prediction.trendDirection === 'INITIAL_BASELINE' ? (
                  <>
                    <div className="d-flex align-items-baseline gap-2">
                      <span className="badge bg-primary-subtle text-primary border border-primary-subtle fs-7 fw-bold px-2 py-1">
                        INITIAL BASELINE
                      </span>
                    </div>
                    <small className="text-muted fs-8 d-block mt-1">
                      Based on {prediction.householdSize || 2} declared occupants
                    </small>
                  </>
                ) : (
                  <>
                    <div className="d-flex align-items-baseline gap-2">
                      <h3
                        className={`fw-extrabold mb-0 ${
                          prediction.trendDirection === 'DECREASE'
                            ? 'text-success'
                            : prediction.trendDirection === 'INCREASE'
                            ? 'text-danger'
                            : 'text-dark'
                        }`}
                      >
                        {prediction.consumptionDeltaPercentage > 0 ? '+' : ''}
                        {prediction.consumptionDeltaPercentage?.toFixed(1) || '0.0'}%
                      </h3>
                      <span
                        className={`badge ${
                          prediction.trendDirection === 'DECREASE'
                            ? 'bg-success-subtle text-success border border-success-subtle'
                            : prediction.trendDirection === 'INCREASE'
                            ? 'bg-danger-subtle text-danger border border-danger-subtle'
                            : 'bg-light text-muted border'
                        } fs-8 fw-semibold`}
                      >
                        {prediction.trendDirection}
                      </span>
                    </div>
                    <small className="text-muted fs-8 d-block mt-1">
                      {prediction.consumptionDeltaKl > 0 ? '+' : ''}
                      {prediction.consumptionDeltaKl?.toFixed(2)} kL vs previous
                    </small>
                  </>
                )}
              </div>
            </div>
          </div>

          {/* AI Recommendation Alert */}
          {prediction.recommendation && (
            <div
              className={`alert ${
                prediction.isNewResident
                  ? 'alert-primary border-primary-subtle'
                  : prediction.trendDirection === 'INCREASE'
                  ? 'alert-warning border-warning-subtle'
                  : 'alert-success border-success-subtle'
              } border rounded-3 p-3 mb-4 d-flex align-items-start gap-2.5`}
            >
              <i
                className={`bi ${
                  prediction.isNewResident
                    ? 'bi-info-circle-fill text-primary'
                    : prediction.trendDirection === 'INCREASE'
                    ? 'bi-lightbulb-fill text-warning'
                    : 'bi-check-circle-fill text-success'
                } fs-5 mt-0.5`}
              ></i>
              <div>
                <strong className="d-block fs-7 text-dark mb-0.5">
                  {prediction.isNewResident ? 'New Resident Baseline Projection:' : 'Smart Water Conservation Advisory:'}
                </strong>
                <p className="fs-7 text-dark mb-0">{prediction.recommendation}</p>
              </div>
            </div>
          )}

          {/* Chart & Cost Breakdown Row */}
          <div className="row g-4">
            {/* Chart Column */}
            <div className="col-lg-7">
              <div className="p-3 rounded-3 border bg-white h-100">
                <div className="d-flex align-items-center justify-content-between mb-2">
                  <span className="fw-bold text-dark fs-7">
                    Historical vs Next-Month Forecast
                  </span>
                  <div className="d-flex align-items-center gap-2 fs-8">
                    <span className="d-flex align-items-center gap-1">
                      <span
                        style={{
                          display: 'inline-block',
                          width: 10,
                          height: 10,
                          backgroundColor: '#005F73',
                          borderRadius: 2,
                        }}
                      ></span>
                      Actual History
                    </span>
                    <span className="d-flex align-items-center gap-1">
                      <span
                        style={{
                          display: 'inline-block',
                          width: 10,
                          height: 10,
                          backgroundColor: '#10B981',
                          borderRadius: 2,
                        }}
                      ></span>
                      AI Forecast
                    </span>
                  </div>
                </div>

                <div style={{ width: '100%', height: 230 }}>
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart
                      data={prediction.historicalData || []}
                      margin={{ top: 10, right: 20, left: -10, bottom: 0 }}
                    >
                      <CartesianGrid strokeDasharray="3 3" stroke="#F1F5F9" />
                      <XAxis
                        dataKey="label"
                        stroke="#64748B"
                        fontSize={11}
                        tickLine={false}
                      />
                      <YAxis
                        stroke="#64748B"
                        fontSize={11}
                        tickLine={false}
                        unit=" kL"
                      />
                      <Tooltip
                        contentStyle={{
                          backgroundColor: '#003554',
                          borderRadius: '8px',
                          color: '#fff',
                          border: 'none',
                          fontSize: '12px',
                        }}
                        formatter={(val, name, item) => [
                          `${val} kL (${Math.round(val * 1000).toLocaleString()} L)`,
                          item.payload.isPredicted ? 'Forecasted' : 'Actual Usage',
                        ]}
                      />
                      <Bar dataKey="consumptionKl" radius={[4, 4, 0, 0]}>
                        {(prediction.historicalData || []).map((entry, index) => (
                          <Cell
                            key={`cell-${index}`}
                            fill={entry.isPredicted ? '#10B981' : '#005F73'}
                          />
                        ))}
                      </Bar>
                    </BarChart>
                  </ResponsiveContainer>
                </div>
                {prediction.isNewResident && (
                  <div className="mt-2 pt-2 border-top text-center text-muted fs-8 d-flex align-items-center justify-content-center gap-1.5">
                    <i className="bi bi-info-circle text-primary"></i>
                    <span>Initial household projection. Past usage comparison bars will appear here once your monthly meter readings are recorded.</span>
                  </div>
                )}
              </div>
            </div>

            {/* Estimated Tariff Breakdown Column */}
            <div className="col-lg-5">
              <div className="p-3 rounded-3 border bg-light h-100">
                <span className="fw-bold text-dark fs-7 d-block mb-2">
                  Estimated Tariff Calculation Breakdown
                </span>
                <div className="bg-white rounded-2 border p-2.5 mb-2">
                  <div className="d-flex justify-content-between align-items-center fs-8 py-1 border-bottom">
                    <span className="text-muted">
                      Tier 1 ({prediction.tierBreakdown?.tier1Units || 0} kL @ ₹
                      {prediction.tierBreakdown?.tier1Rate || 0}/kL)
                    </span>
                    <strong className="text-dark">
                      {formatCurrency(prediction.tierBreakdown?.tier1Amount)}
                    </strong>
                  </div>
                  <div className="d-flex justify-content-between align-items-center fs-8 py-1 border-bottom">
                    <span className="text-muted">
                      Tier 2 ({prediction.tierBreakdown?.tier2Units || 0} kL @ ₹
                      {prediction.tierBreakdown?.tier2Rate || 0}/kL)
                    </span>
                    <strong className="text-dark">
                      {formatCurrency(prediction.tierBreakdown?.tier2Amount)}
                    </strong>
                  </div>
                  <div className="d-flex justify-content-between align-items-center fs-8 py-1 border-bottom">
                    <span className="text-muted">
                      Tier 3 ({prediction.tierBreakdown?.tier3Units || 0} kL @ ₹
                      {prediction.tierBreakdown?.tier3Rate || 0}/kL)
                    </span>
                    <strong className="text-dark">
                      {formatCurrency(prediction.tierBreakdown?.tier3Amount)}
                    </strong>
                  </div>
                  <div className="d-flex justify-content-between align-items-center fs-8 py-1 border-bottom">
                    <span className="text-muted">Fixed Service Charge</span>
                    <strong className="text-dark">
                      {formatCurrency(prediction.tierBreakdown?.fixedCharge)}
                    </strong>
                  </div>
                  <div className="d-flex justify-content-between align-items-center fs-7 pt-2 fw-bold">
                    <span className="text-dark">Total Estimated</span>
                    <span className="text-primary fs-6">
                      {formatCurrency(prediction.estimatedAmount)}
                    </span>
                  </div>
                </div>

                <div className="d-flex align-items-center justify-content-between text-muted fs-8 px-1">
                  <span>Model: {prediction.modelName}</span>
                  <span>{prediction.isNewResident ? 'Type: Initial Baseline' : `Version: ${prediction.modelVersion}`}</span>
                </div>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
