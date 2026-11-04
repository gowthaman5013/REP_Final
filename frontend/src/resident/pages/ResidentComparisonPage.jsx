import React, { useState, useEffect } from 'react';
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  Cell
} from 'recharts';
import API from '../../shared/services/api';
import LoadingSpinner from '../../shared/components/LoadingSpinner';

export default function ResidentComparisonPage() {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    API.get('/resident/consumption-comparison')
      .then(res => setData(res.data))
      .catch(err => console.error(err))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <LoadingSpinner text="Loading Water Consumption Comparison..." />;

  const householdUsage = data?.householdUsage || 0;
  const apartmentAvg = data?.apartmentAverage || 0;
  const similarAvg = data?.similarHouseholdAverage || 0;
  const isAbove = householdUsage > apartmentAvg;

  const barData = [
    { name: 'Your Household', usage: householdUsage, color: isAbove ? '#DC2626' : '#0A9396' },
    { name: 'Apartment Average', usage: apartmentAvg, color: '#005F73' },
    { name: 'Similar Households', usage: similarAvg, color: '#94D2BD' }
  ];

  return (
    <div className="container-fluid py-4">
      {/* Header Banner */}
      <div className="card glass-card border-0 shadow-sm p-4 mb-4 bg-white rounded-4">
        <div className="d-flex align-items-center justify-content-between flex-wrap gap-3">
          <div>
            <h2 className="fw-bold mb-1 text-dark">Water Consumption Comparison</h2>
            <p className="text-muted small mb-0">Benchmark your household water usage against apartment & peer averages</p>
          </div>
          <span className="badge bg-light text-dark border px-3 py-2 fw-semibold fs-7">
            <i className="bi bi-bar-chart-fill me-1.5" style={{ color: '#5A738E' }}></i> Peer Benchmarking
          </span>
        </div>
      </div>

      {/* Conservation Message Banner */}
      <div
        className="card border-0 shadow-sm p-4 mb-4 rounded-4"
        style={{
          backgroundColor: isAbove ? '#FEF2F2' : '#E8F8F5',
          borderLeft: `5px solid ${isAbove ? '#DC2626' : '#0E6251'}`
        }}
      >
        <div className="d-flex align-items-center gap-3">
          <div
            className="rounded-circle p-3 d-flex align-items-center justify-content-center text-white"
            style={{ width: 50, height: 50, backgroundColor: isAbove ? '#DC2626' : '#0E6251' }}
          >
            <i className={`bi ${isAbove ? 'bi-exclamation-circle-fill' : 'bi-check-circle-fill'} fs-4`}></i>
          </div>
          <div>
            <h5 className="fw-bold mb-1" style={{ color: isAbove ? '#DC2626' : '#0E6251' }}>
              {isAbove ? 'Higher Than Average Consumption' : 'Efficient Consumption'}
            </h5>
            <p className="mb-0 fs-7 fw-semibold text-dark">{data?.message}</p>
          </div>
        </div>
      </div>

      {/* Grid: Benchmark Metrics & Recharts Bar Chart */}
      <div className="row g-4 mb-4">
        {/* Metric Cards */}
        <div className="col-lg-4">
          <div className="d-flex flex-column gap-3 h-100">
            <div className="card glass-card border shadow-sm p-4 bg-white rounded-4 flex-grow-1">
              <small className="text-muted fw-semibold text-uppercase fs-8 d-block mb-1">Your Household Usage</small>
              <h2 className="fw-extrabold text-dark mb-0">{householdUsage} kL</h2>
              <small className="text-muted fs-8">Current Billing Period</small>
            </div>

            <div className="card glass-card border shadow-sm p-4 bg-white rounded-4 flex-grow-1">
              <small className="text-muted fw-semibold text-uppercase fs-8 d-block mb-1">Apartment Average</small>
              <h2 className="fw-extrabold text-dark mb-0">{apartmentAvg} kL</h2>
              <small className="text-muted fs-8">Building Residents Average</small>
            </div>

            <div className="card glass-card border shadow-sm p-4 bg-white rounded-4 flex-grow-1">
              <small className="text-muted fw-semibold text-uppercase fs-8 d-block mb-1">Similar Households Average</small>
              <h2 className="fw-extrabold text-dark mb-0">{similarAvg} kL</h2>
              <small className="text-muted fs-8">Peer Benchmark</small>
            </div>
          </div>
        </div>

        {/* Recharts BarChart */}
        <div className="col-lg-8">
          <div className="card glass-card border shadow-sm p-4 bg-white rounded-4 h-100">
            <div className="d-flex align-items-center justify-content-between mb-3">
              <h5 className="fw-bold mb-0 text-dark">Usage Comparison Chart</h5>
              <span className="badge bg-light text-dark border px-3 py-1.5 fs-8">kL Units</span>
            </div>

            <div style={{ width: '100%', height: 300 }}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={barData} margin={{ top: 20, right: 30, left: 0, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#F1F5F9" />
                  <XAxis dataKey="name" stroke="#475569" fontSize={12} tickLine={false} />
                  <YAxis stroke="#475569" fontSize={12} tickLine={false} unit=" kL" />
                  <Tooltip
                    contentStyle={{ backgroundColor: '#003554', borderRadius: '8px', color: '#fff', border: 'none' }}
                    itemStyle={{ color: '#E3F6FF', fontWeight: 'bold' }}
                    formatter={(val) => [`${val} kL`, 'Usage']}
                  />
                  <Legend wrapperStyle={{ fontSize: '12px', paddingTop: '10px' }} />
                  <Bar dataKey="usage" name="Consumption (kL)" radius={[8, 8, 0, 0]}>
                    {barData.map((entry, index) => (
                      <Cell key={`cell-${index}`} fill={entry.color} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
