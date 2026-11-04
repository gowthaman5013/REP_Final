import React, { useState, useEffect, useMemo } from 'react';
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

export default function OwnerUsageComparisonPage() {
  const [data, setData] = useState(null);
  const [buildings, setBuildings] = useState([]);
  const [selectedBuildingId, setSelectedBuildingId] = useState('');
  const [cycles, setCycles] = useState([]);
  const [selectedCycleId, setSelectedCycleId] = useState('');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [sortBy, setSortBy] = useState('apartment'); // 'apartment', 'usage'
  const [filterView, setFilterView] = useState('all'); // 'all', 'active'

  // 1. Fetch buildings on mount
  useEffect(() => {
    API.get('/owners/buildings')
      .then(res => {
        const bList = res.data || [];
        setBuildings(bList);
        if (bList.length) {
          setSelectedBuildingId(String(bList[0].buildingId));
        }
      })
      .catch(err => console.error('Failed to load buildings', err));
  }, []);

  // 2. Fetch cycles whenever selectedBuildingId changes
  useEffect(() => {
    if (!selectedBuildingId) return;

    API.get(`/api/owner/billing-cycles?buildingId=${selectedBuildingId}`)
      .catch(() => API.get('/api/billing-cycles'))
      .then(res => {
        const cList = res.data || [];
        setCycles(cList);
        // Default to the first cycle or empty for latest
        if (cList.length > 0) {
          setSelectedCycleId(String(cList[0].id));
        } else {
          setSelectedCycleId('');
        }
      })
      .catch(err => {
        console.error('Failed to load cycles', err);
        setCycles([]);
        setSelectedCycleId('');
      });
  }, [selectedBuildingId]);

  // 3. Fetch comparison data for building & cycle
  const fetchComparison = (bId, cId, isInitial = false) => {
    if (!bId) return;
    if (isInitial) setLoading(true);
    else setRefreshing(true);

    let url = `/owners/usage-comparison?buildingId=${bId}`;
    if (cId) {
      url += `&billingCycleId=${cId}`;
    }

    API.get(url)
      .then(res => setData(res.data))
      .catch(err => console.error('Failed to load usage comparison', err))
      .finally(() => {
        setLoading(false);
        setRefreshing(false);
      });
  };

  useEffect(() => {
    if (selectedBuildingId) {
      fetchComparison(selectedBuildingId, selectedCycleId, !data);
    }
  }, [selectedBuildingId, selectedCycleId]);

  if (loading && !data) return <LoadingSpinner text="Loading Household Usage Comparison..." />;

  const households = data?.households || [];
  const bAvg = data?.buildingAverage || 0;
  const totalApartmentsCount = data?.totalApartments || households.length;
  const activeApartmentsCount = data?.activeApartments || households.filter(h => (h.unitsConsumed || 0) > 0).length;

  // Filter based on view: 'all' vs 'active'
  const displayHouseholds = filterView === 'active'
    ? households.filter(h => (h.unitsConsumed || 0) > 0)
    : households;

  // Sorting
  const sortedHouseholds = [...displayHouseholds].sort((a, b) => {
    if (sortBy === 'usage') {
      return (b.unitsConsumed || 0) - (a.unitsConsumed || 0);
    }
    return String(a.apartmentNumber || '').localeCompare(String(b.apartmentNumber || ''), undefined, { numeric: true });
  });

  const chartData = sortedHouseholds.map(h => {
    const units = Number(h.unitsConsumed || 0);
    const isAbove = bAvg > 0 && units > bAvg;
    const isZero = units === 0;

    return {
      apartment: `Apt ${h.apartmentNumber}`,
      consumption: units,
      resident: h.residentName || 'Vacant',
      isAbove,
      isZero,
      meterNumber: h.meterNumber
    };
  });

  return (
    <div className="container-fluid py-4">
      {/* Header Banner & Controls */}
      <div className="card glass-card border-0 shadow-sm p-4 mb-4 bg-white rounded-4">
        <div className="d-flex align-items-center justify-content-between flex-wrap gap-3">
          <div>
            <h2 className="fw-bold mb-1 text-dark">Household Usage Comparison</h2>
            <p className="text-muted small mb-0">
              Compare water consumption across all {totalApartmentsCount} apartments in your building
            </p>
          </div>

          <div className="d-flex align-items-center gap-3 flex-wrap">
            {/* Building Selection */}
            {buildings.length > 0 && (
              <div className="d-flex align-items-center bg-white p-1.5 px-2.5 rounded-3 border shadow-sm" style={{ minWidth: '220px' }}>
                <i className="bi bi-building text-primary me-2 fs-6"></i>
                <div className="flex-grow-1">
                  <small className="text-muted d-block" style={{ fontSize: '0.7rem', lineHeight: 1 }}>Property / Building</small>
                  <select
                    className="form-select form-select-sm border-0 p-0 fw-bold bg-transparent"
                    value={selectedBuildingId}
                    onChange={e => setSelectedBuildingId(e.target.value)}
                    id="building-select"
                  >
                    {buildings.map(b => (
                      <option key={b.buildingId} value={b.buildingId}>
                        {b.buildingName} ({b.buildingNumber})
                      </option>
                    ))}
                  </select>
                </div>
              </div>
            )}

            {/* Billing Cycle Selection */}
            {cycles.length > 0 && (
              <div className="d-flex align-items-center bg-white p-1.5 px-2.5 rounded-3 border shadow-sm" style={{ minWidth: '220px' }}>
                <i className="bi bi-calendar-event text-info me-2 fs-6"></i>
                <div className="flex-grow-1">
                  <small className="text-muted d-block" style={{ fontSize: '0.7rem', lineHeight: 1 }}>Billing Cycle</small>
                  <select
                    className="form-select form-select-sm border-0 p-0 fw-bold bg-transparent"
                    value={selectedCycleId}
                    onChange={e => setSelectedCycleId(e.target.value)}
                    id="cycle-select"
                  >
                    {cycles.map(c => {
                      const label = c.billingMonth 
                        ? `${c.billingMonth.substring(0, 7)} (Cycle #${c.id})`
                        : `Cycle #${c.id} (${c.startDate || ''})`;
                      return (
                        <option key={c.id} value={c.id}>
                          {label} {c.status ? `[${c.status}]` : ''}
                        </option>
                      );
                    })}
                  </select>
                </div>
              </div>
            )}

            {/* Quick Refresh */}
            <button
              className="btn btn-outline-secondary btn-sm p-2 px-2.5 rounded-3 shadow-sm"
              onClick={() => fetchComparison(selectedBuildingId, selectedCycleId, false)}
              title="Refresh comparison data"
              disabled={refreshing}
            >
              <i className={`bi bi-arrow-clockwise ${refreshing ? 'spin' : ''}`}></i>
            </button>
          </div>
        </div>
      </div>

      {/* Summary Metrics Banner */}
      <div className="row g-3 mb-4">
        <div className="col-12 col-md-4">
          <div className="card glass-card border shadow-sm p-3 bg-white rounded-4">
            <small className="text-muted fw-semibold text-uppercase fs-8 d-block mb-1">Building Average Consumption</small>
            <h3 className="fw-extrabold text-dark mb-0">
              {bAvg} <span className="fs-6 text-muted fw-normal">kL / Household</span>
            </h3>
            <small className="text-muted mt-1 d-block fs-8">
              Calculated across active consuming households
            </small>
          </div>
        </div>

        <div className="col-12 col-md-4">
          <div className="card glass-card border shadow-sm p-3 bg-white rounded-4">
            <small className="text-muted fw-semibold text-uppercase fs-8 d-block mb-1">Total Apartments Tracked</small>
            <h3 className="fw-extrabold text-dark mb-0">
              {totalApartmentsCount} <span className="fs-6 text-muted fw-normal">Apartments</span>
            </h3>
            <small className="text-muted mt-1 d-block fs-8">
              <span className="text-success fw-bold">{activeApartmentsCount} active</span> &bull; {totalApartmentsCount - activeApartmentsCount} vacant / zero usage
            </small>
          </div>
        </div>

        <div className="col-12 col-md-4">
          <div className="card glass-card border shadow-sm p-3 bg-white rounded-4">
            <small className="text-muted fw-semibold text-uppercase fs-8 d-block mb-1">Above Average Households</small>
            <h3 className="fw-extrabold mb-0 text-danger">
              {households.filter(h => (h.unitsConsumed || 0) > bAvg && (h.unitsConsumed || 0) > 0).length} <span className="fs-6 text-muted fw-normal">Households</span>
            </h3>
            <small className="text-muted mt-1 d-block fs-8">
              Exceeding building benchmark of {bAvg} kL
            </small>
          </div>
        </div>
      </div>

      {/* Recharts BarChart Visualization */}
      <div className="card glass-card border shadow-sm p-4 mb-4 bg-white rounded-4">
        <div className="d-flex align-items-center justify-content-between mb-3 flex-wrap gap-2">
          <div>
            <h5 className="fw-bold mb-0 text-dark">Apartment Consumption Chart</h5>
            <small className="text-muted fs-8">
              Displaying {chartData.length} of {totalApartmentsCount} apartments in {data?.buildingName || 'Building'}
            </small>
          </div>

          <div className="d-flex align-items-center gap-2 flex-wrap">
            {/* View Filter: All Apartments vs Active Only */}
            <div className="btn-group btn-group-sm me-2" role="group">
              <button
                className={`btn btn-sm ${filterView === 'all' ? 'btn-primary' : 'btn-outline-secondary'}`}
                onClick={() => setFilterView('all')}
              >
                All Apartments ({totalApartmentsCount})
              </button>
              <button
                className={`btn btn-sm ${filterView === 'active' ? 'btn-primary' : 'btn-outline-secondary'}`}
                onClick={() => setFilterView('active')}
              >
                Active Only ({activeApartmentsCount})
              </button>
            </div>

            {/* Sort Buttons */}
            <span className="text-muted fs-8">Sort:</span>
            <button
              className={`btn btn-sm ${sortBy === 'apartment' ? 'btn-dark' : 'btn-outline-secondary'}`}
              onClick={() => setSortBy('apartment')}
            >
              Apartment #
            </button>
            <button
              className={`btn btn-sm ${sortBy === 'usage' ? 'btn-dark' : 'btn-outline-secondary'}`}
              onClick={() => setSortBy('usage')}
            >
              Highest Consumption
            </button>
          </div>
        </div>

        {chartData.length ? (
          <div style={{ width: '100%', height: 340 }}>
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={chartData} margin={{ top: 20, right: 30, left: 0, bottom: 20 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#F1F5F9" />
                <XAxis 
                  dataKey="apartment" 
                  stroke="#475569" 
                  fontSize={11} 
                  tickLine={false} 
                  interval={0}
                  angle={chartData.length > 10 ? -45 : 0}
                  textAnchor={chartData.length > 10 ? 'end' : 'middle'}
                  height={chartData.length > 10 ? 50 : 30}
                />
                <YAxis stroke="#475569" fontSize={12} tickLine={false} unit=" kL" />
                <Tooltip
                  contentStyle={{ backgroundColor: '#003554', borderRadius: '8px', color: '#fff', border: 'none' }}
                  itemStyle={{ color: '#E3F6FF', fontWeight: 'bold' }}
                  formatter={(val, name, props) => [
                    `${val} kL (${props.payload.resident}) [Meter: ${props.payload.meterNumber}]`,
                    'Consumption'
                  ]}
                />
                <Legend wrapperStyle={{ fontSize: '12px', paddingTop: '10px' }} />
                <Bar dataKey="consumption" name="Household Usage (kL)" radius={[6, 6, 0, 0]}>
                  {chartData.map((entry, index) => (
                    <Cell
                      key={`cell-${index}`}
                      fill={entry.isZero ? '#CBD5E1' : entry.isAbove ? '#DC2626' : '#005F73'}
                    />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <div className="p-4 text-center text-muted bg-light rounded-3">No apartment consumption records found.</div>
        )}
      </div>

      {/* Household Table List */}
      <div className="card glass-card border shadow-sm bg-white rounded-4 overflow-hidden">
        <div className="card-header bg-transparent border-0 pt-4 px-4 d-flex align-items-center justify-content-between">
          <h5 className="fw-bold mb-0 text-dark">Household Usage Breakdown Table</h5>
          <span className="badge bg-light text-dark border px-2.5 py-1.5 fw-semibold">
            {sortedHouseholds.length} apartments shown
          </span>
        </div>
        <div className="card-body p-0">
          <div className="table-responsive">
            <table className="table custom-table mb-0 align-middle">
              <thead>
                <tr>
                  <th className="ps-4">Apartment #</th>
                  <th>Resident Name</th>
                  <th>Meter Number</th>
                  <th>Consumption (kL)</th>
                  <th>Vs Building Average</th>
                  <th className="pe-4">Status Indicator</th>
                </tr>
              </thead>
              <tbody>
                {sortedHouseholds.length ? (
                  sortedHouseholds.map(h => {
                    const units = Number(h.unitsConsumed || 0);
                    const isAbove = bAvg > 0 && units > bAvg;
                    const diff = bAvg > 0 ? Math.round((units - bAvg) * 100.0) / 100.0 : 0;
                    const isZero = units === 0;

                    return (
                      <tr key={h.apartmentNumber || h.residentId}>
                        <td className="ps-4">
                          <span className="badge px-2.5 py-1.5 rounded-2 fw-bold text-dark border" style={{ backgroundColor: '#F1F5F9', borderColor: '#CBD5E1' }}>
                            <i className="bi bi-door-closed me-1 text-secondary"></i>
                            Flat {h.apartmentNumber}
                          </span>
                        </td>
                        <td>
                          {h.residentName && h.residentName !== 'Vacant / Unoccupied' ? (
                            <div className="d-flex align-items-center gap-2">
                              <div className="rounded-circle bg-light text-primary d-flex align-items-center justify-content-center" style={{ width: 24, height: 24, fontSize: '0.75rem' }}>
                                <i className="bi bi-person-fill"></i>
                              </div>
                              <span className="fw-semibold text-dark">{h.residentName}</span>
                            </div>
                          ) : (
                            <span className="badge bg-light text-muted border px-2 py-1 small">
                              Vacant / Unoccupied
                            </span>
                          )}
                        </td>
                        <td><code className="text-dark fw-bold font-monospace">{h.meterNumber}</code></td>
                        <td className="fw-bold text-dark fs-6">{units.toFixed(1)} kL</td>
                        <td className="small">
                          {isZero ? (
                            <span className="text-muted">&mdash;</span>
                          ) : diff > 0 ? (
                            <span className="text-danger fw-bold">+{diff} kL above avg</span>
                          ) : (
                            <span className="text-success fw-bold">{diff} kL below avg</span>
                          )}
                        </td>
                        <td className="pe-4">
                          {isZero ? (
                            <span className="badge bg-light text-secondary border px-2.5 py-1 fw-bold fs-8">
                              VACANT / NO USAGE
                            </span>
                          ) : isAbove ? (
                            <span className="badge bg-danger-subtle text-danger border border-danger-subtle px-3 py-1.5 fw-bold fs-8">
                              HIGH (Above Avg)
                            </span>
                          ) : (
                            <span className="badge bg-success-subtle text-success border border-success-subtle px-3 py-1.5 fw-bold fs-8">
                              NORMAL
                            </span>
                          )}
                        </td>
                      </tr>
                    );
                  })
                ) : (
                  <tr>
                    <td colSpan="6" className="text-center py-4 text-muted">No apartments found for selected building.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}
