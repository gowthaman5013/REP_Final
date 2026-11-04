import React, { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import API from '../../shared/services/api';
import LoadingSpinner from '../../shared/components/LoadingSpinner';

export default function OwnerUsagePage() {
  const navigate = useNavigate();

  const [usageRecords, setUsageRecords] = useState([]);
  const [buildings, setBuildings] = useState([]);
  const [selectedBuildingId, setSelectedBuildingId] = useState('');
  const [apartments, setApartments] = useState([]);
  const [selectedApartment, setSelectedApartment] = useState('');
  const [cycles, setCycles] = useState([]);
  const [selectedCycleId, setSelectedCycleId] = useState('');
  const [searchTerm, setSearchTerm] = useState('');
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  // 1. Fetch buildings on mount
  useEffect(() => {
    API.get('/owners/buildings')
      .then(res => {
        const bList = res.data || [];
        setBuildings(bList);
        if (bList.length > 0) {
          setSelectedBuildingId(String(bList[0].buildingId));
        }
      })
      .catch(err => console.error('Failed to load buildings', err));
  }, []);

  // 2. Fetch cycles and apartments whenever selectedBuildingId changes
  useEffect(() => {
    if (!selectedBuildingId) return;

    // Fetch billing cycles for this building
    API.get(`/api/owner/billing-cycles?buildingId=${selectedBuildingId}`)
      .catch(() => API.get('/api/billing-cycles'))
      .then(cycleRes => {
        setCycles(cycleRes.data || []);
      })
      .catch(err => console.error('Failed to load billing cycles', err));

    // Fetch apartments for this building inventory
    API.get(`/owners/buildings/${selectedBuildingId}/apartments`)
      .then(aptRes => {
        const aptData = aptRes.data?.apartments || aptRes.data || [];
        const aptList = Array.isArray(aptData) ? aptData : [];
        setApartments(aptList);
      })
      .catch(err => {
        console.error('Failed to load apartments for building', err);
        setApartments([]);
      });
  }, [selectedBuildingId]);

  // 3. Fetch usage records
  const fetchUsageRecords = (isInitial = false) => {
    if (isInitial) setLoading(true);
    else setRefreshing(true);

    const params = {};
    if (selectedBuildingId) params.buildingId = selectedBuildingId;
    if (selectedApartment && selectedApartment !== 'ALL') params.apartmentNumber = selectedApartment;
    if (selectedCycleId && selectedCycleId !== 'ALL') params.billingCycleId = selectedCycleId;

    API.get('/usage/records', { params })
      .then(res => {
        setUsageRecords(res.data || []);
      })
      .catch(err => console.error('Failed to fetch usage records', err))
      .finally(() => {
        setLoading(false);
        setRefreshing(false);
      });
  };

  useEffect(() => {
    if (selectedBuildingId) {
      fetchUsageRecords(loading);
    }
  }, [selectedBuildingId, selectedApartment, selectedCycleId]);

  // Extract unique apartment list from records + building inventory
  const combinedApartments = useMemo(() => {
    const aptSet = new Map();

    // From building apartments API
    if (Array.isArray(apartments)) {
      apartments.forEach(a => {
        const num = a.roomNumber || a.apartmentName || a.apartmentNumber;
        if (num) {
          aptSet.set(String(num).trim(), {
            key: String(num).trim(),
            label: `Flat ${num}`,
            floor: a.floorNumber
          });
        }
      });
    }

    // From usage records
    if (Array.isArray(usageRecords)) {
      usageRecords.forEach(u => {
        if (u.apartmentNumber) {
          const num = String(u.apartmentNumber).trim();
          if (!aptSet.has(num)) {
            aptSet.set(num, {
              key: num,
              label: `Flat ${num}`
            });
          }
        }
      });
    }

    return Array.from(aptSet.values()).sort((a, b) => a.key.localeCompare(b.key, undefined, { numeric: true }));
  }, [apartments, usageRecords]);

  // Client-side filtering as an immediate reactive safeguard
  const filteredRecords = useMemo(() => {
    return usageRecords.filter(u => {
      // Building filter
      if (selectedBuildingId && u.buildingId && String(u.buildingId) !== String(selectedBuildingId)) {
        return false;
      }
      // Apartment filter
      if (selectedApartment && selectedApartment !== 'ALL') {
        const uApt = String(u.apartmentNumber || '').trim().toLowerCase();
        const sApt = String(selectedApartment).trim().toLowerCase();
        if (uApt !== sApt && !uApt.includes(sApt)) {
          return false;
        }
      }
      // Cycle filter
      if (selectedCycleId && selectedCycleId !== 'ALL') {
        if (String(u.billingCycleId) !== String(selectedCycleId)) {
          return false;
        }
      }
      // Search term
      if (searchTerm.trim()) {
        const term = searchTerm.toLowerCase();
        const matchMeter = (u.meterNumber || '').toLowerCase().includes(term);
        const matchResident = (u.residentName || '').toLowerCase().includes(term);
        const matchApt = (u.apartmentNumber || '').toLowerCase().includes(term);
        if (!matchMeter && !matchResident && !matchApt) return false;
      }
      return true;
    });
  }, [usageRecords, selectedBuildingId, selectedApartment, selectedCycleId, searchTerm]);

  // Statistics
  const totalUnits = useMemo(() => {
    return filteredRecords.reduce((acc, r) => acc + (Number(r.unitsConsumed) || 0), 0);
  }, [filteredRecords]);

  const hasActiveFilters = Boolean(selectedApartment || selectedCycleId || searchTerm);

  const handleResetFilters = () => {
    setSelectedApartment('');
    setSelectedCycleId('');
    setSearchTerm('');
  };

  if (loading && !buildings.length) {
    return <LoadingSpinner text="Fetching water meter readings..." />;
  }

  return (
    <div className="container-fluid py-4">
      {/* Header section */}
      <div className="d-flex align-items-center justify-content-between mb-4 flex-wrap gap-3">
        <div>
          <h2 className="fw-bold mb-1 text-dark">Water Meter Consumption Logs</h2>
          <p className="text-muted small mb-0">
            Digital logbook to view household readings per billing cycle imported via Bulk Billing
          </p>
        </div>

        {/* Action Controls: Apartment selection & Bill cycle month selection */}
        <div className="d-flex align-items-center gap-2 flex-wrap">
          {/* Building Selection */}
          {buildings.length > 0 && (
            <div className="d-flex align-items-center bg-white p-1.5 px-2.5 rounded-3 shadow-sm border" style={{ minWidth: '220px' }}>
              <i className="bi bi-building text-primary me-2 fs-6"></i>
              <div className="flex-grow-1">
                <small className="text-muted d-block" style={{ fontSize: '0.7rem', lineHeight: 1 }}>Property / Building</small>
                <select
                  className="form-select form-select-sm border-0 p-0 fw-bold bg-transparent"
                  value={selectedBuildingId}
                  onChange={e => {
                    setSelectedBuildingId(e.target.value);
                    setSelectedApartment('');
                    setSelectedCycleId('');
                  }}
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

          {/* Apartment Selection */}
          <div className="d-flex align-items-center bg-white p-1.5 px-2.5 rounded-3 shadow-sm border" style={{ minWidth: '180px' }}>
            <i className="bi bi-door-open text-success me-2 fs-6"></i>
            <div className="flex-grow-1">
              <small className="text-muted d-block" style={{ fontSize: '0.7rem', lineHeight: 1 }}>Apartment / Room</small>
              <select
                className="form-select form-select-sm border-0 p-0 fw-bold bg-transparent"
                value={selectedApartment}
                onChange={e => setSelectedApartment(e.target.value)}
                id="apartment-select"
              >
                <option value="">All Apartments</option>
                {combinedApartments.map(apt => (
                  <option key={apt.key} value={apt.key}>
                    {apt.label}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Billing Cycle Month Selection */}
          <div className="d-flex align-items-center bg-white p-1.5 px-2.5 rounded-3 shadow-sm border" style={{ minWidth: '220px' }}>
            <i className="bi bi-calendar-event text-info me-2 fs-6"></i>
            <div className="flex-grow-1">
              <small className="text-muted d-block" style={{ fontSize: '0.7rem', lineHeight: 1 }}>Billing Cycle Month</small>
              <select
                className="form-select form-select-sm border-0 p-0 fw-bold bg-transparent"
                value={selectedCycleId}
                onChange={e => setSelectedCycleId(e.target.value)}
                id="cycle-select"
              >
                <option value="">All Billing Cycles</option>
                {cycles.map(c => {
                  const cycleLabel = c.billingMonth 
                    ? `${c.billingMonth.substring(0, 7)} (Cycle #${c.id})`
                    : `Cycle #${c.id} (${c.startDate || ''})`;
                  return (
                    <option key={c.id} value={c.id}>
                      {cycleLabel} {c.status ? `[${c.status}]` : ''}
                    </option>
                  );
                })}
              </select>
            </div>
          </div>

          {/* Quick Refresh */}
          <button
            className="btn btn-outline-secondary btn-sm p-2 px-2.5 rounded-3 shadow-sm"
            onClick={() => fetchUsageRecords(false)}
            title="Refresh logs"
            disabled={refreshing}
          >
            <i className={`bi bi-arrow-clockwise ${refreshing ? 'spin' : ''}`}></i>
          </button>

          {/* Reset Filters */}
          {hasActiveFilters && (
            <button
              className="btn btn-link text-decoration-none text-danger btn-sm p-1 fw-semibold"
              onClick={handleResetFilters}
              title="Clear all filters"
            >
              <i className="bi bi-x-circle me-1"></i> Clear
            </button>
          )}
        </div>
      </div>

      {/* Summary Info Cards */}
      <div className="row g-3 mb-4">
        <div className="col-12 col-md-4">
          <div className="card border-0 shadow-sm rounded-3 p-3 bg-white">
            <div className="d-flex align-items-center justify-content-between">
              <div>
                <span className="text-muted small fw-semibold">Filtered Log Entries</span>
                <h4 className="fw-bold mb-0 text-dark mt-1">{filteredRecords.length}</h4>
              </div>
              <div className="rounded-circle bg-light p-2.5 d-flex align-items-center justify-content-center text-primary" style={{ width: 44, height: 44 }}>
                <i className="bi bi-list-check fs-5"></i>
              </div>
            </div>
          </div>
        </div>

        <div className="col-12 col-md-4">
          <div className="card border-0 shadow-sm rounded-3 p-3 bg-white">
            <div className="d-flex align-items-center justify-content-between">
              <div>
                <span className="text-muted small fw-semibold">Total Consumed</span>
                <h4 className="fw-bold mb-0 text-primary mt-1">{totalUnits.toFixed(1)} <span className="fs-6 text-muted fw-normal">kL</span></h4>
              </div>
              <div className="rounded-circle bg-light p-2.5 d-flex align-items-center justify-content-center text-info" style={{ width: 44, height: 44 }}>
                <i className="bi bi-droplet-half fs-5"></i>
              </div>
            </div>
          </div>
        </div>

        <div className="col-12 col-md-4">
          <div className="card border-0 shadow-sm rounded-3 p-3 bg-white">
            <div className="d-flex align-items-center justify-content-between">
              <div>
                <span className="text-muted small fw-semibold">Active Filter Scope</span>
                <div className="fw-bold text-dark mt-1 text-truncate" style={{ maxWidth: '240px' }}>
                  {selectedApartment ? `Flat ${selectedApartment}` : 'All Apartments'} &bull;{' '}
                  {selectedCycleId ? `Cycle #${selectedCycleId}` : 'All Cycles'}
                </div>
              </div>
              <div className="rounded-circle bg-light p-2.5 d-flex align-items-center justify-content-center text-success" style={{ width: 44, height: 44 }}>
                <i className="bi bi-funnel-fill fs-5"></i>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Main Table Card */}
      <div className="card glass-card border shadow-sm bg-white">
        {/* Table Toolbar */}
        <div className="card-header bg-white border-bottom py-3 px-3 d-flex align-items-center justify-content-between flex-wrap gap-2">
          <div className="d-flex align-items-center gap-2">
            <h6 className="fw-bold mb-0 text-dark">
              <i className="bi bi-journal-text me-2 text-primary"></i>
              Consumption Records
            </h6>
            <span className="badge bg-light text-dark border px-2 py-1 small">
              {filteredRecords.length} records
            </span>
          </div>

          <div className="d-flex align-items-center gap-2">
            <div className="input-group input-group-sm" style={{ width: '220px' }}>
              <span className="input-group-text bg-light border-end-0">
                <i className="bi bi-search text-muted"></i>
              </span>
              <input
                type="text"
                className="form-control bg-light border-start-0"
                placeholder="Search meter, resident..."
                value={searchTerm}
                onChange={e => setSearchTerm(e.target.value)}
              />
              {searchTerm && (
                <button className="btn btn-outline-secondary" type="button" onClick={() => setSearchTerm('')}>
                  &times;
                </button>
              )}
            </div>
          </div>
        </div>

        <div className="card-body p-0">
          <div className="table-responsive">
            <table className="table custom-table mb-0 align-middle">
              <thead className="bg-light">
                <tr>
                  <th className="ps-3">Meter Number</th>
                  <th>Apartment</th>
                  <th>Resident</th>
                  <th>Month / Cycle</th>
                  <th>Previous Reading</th>
                  <th>Current Reading</th>
                  <th>Units Consumed</th>
                  <th className="pe-3">Recorded At</th>
                </tr>
              </thead>
              <tbody>
                {filteredRecords.length > 0 ? (
                  filteredRecords.map(u => (
                    <tr key={u.usageId || `${u.meterId}-${u.billingCycleId}`}>
                      <td className="ps-3">
                        <span className="font-monospace text-dark fw-bold">{u.meterNumber}</span>
                      </td>
                      <td>
                        <span className="badge px-2.5 py-1.5 rounded-2 fw-bold text-dark border" style={{ backgroundColor: '#F1F5F9', borderColor: '#CBD5E1' }}>
                          <i className="bi bi-door-closed me-1 text-secondary"></i>
                          {u.apartmentNumber ? `Flat ${u.apartmentNumber}` : 'N/A'}
                        </span>
                      </td>
                      <td className="fw-bold text-dark">
                        <div className="d-flex align-items-center gap-2">
                          <div className="rounded-circle bg-light text-primary d-flex align-items-center justify-content-center" style={{ width: 26, height: 26, fontSize: '0.75rem' }}>
                            <i className="bi bi-person-fill"></i>
                          </div>
                          <span>{u.residentName || 'Unassigned'}</span>
                        </div>
                      </td>
                      <td>
                        <div>
                          <span className="text-dark fw-bold">{u.month ? `${u.month}/${u.year}` : 'N/A'}</span>
                          {u.billingCycleId && (
                            <span className="badge bg-light text-muted border ms-1.5 fs-8">
                              Cycle #{u.billingCycleId}
                            </span>
                          )}
                        </div>
                      </td>
                      <td className="text-muted">{Number(u.previousReading || 0).toFixed(1)} m³</td>
                      <td className="text-dark fw-semibold">{Number(u.currentReading || 0).toFixed(1)} m³</td>
                      <td>
                        <span className="fw-bold text-dark fs-6">{Number(u.unitsConsumed || 0).toFixed(1)} kL</span>
                      </td>
                      <td className="text-muted small pe-3">
                        {u.recordedAt ? u.recordedAt.substring(0, 10) : 'N/A'}
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan="9" className="text-center py-5 text-muted">
                      <div className="py-4">
                        <i className="bi bi-inbox fs-1 text-muted d-block mb-2"></i>
                        <h6 className="fw-bold text-dark mb-1">No meter reading logs found</h6>
                        <p className="text-muted small mb-3">
                          {hasActiveFilters
                            ? 'No records match your selected apartment or billing cycle filters.'
                            : 'No consumption logs have been recorded or imported for this building yet.'}
                        </p>
                        {hasActiveFilters ? (
                          <button className="btn btn-outline-dark btn-sm fw-bold" onClick={handleResetFilters}>
                            <i className="bi bi-arrow-counterclockwise me-1"></i> Reset Filters
                          </button>
                        ) : (
                          <button
                            className="btn btn-primary btn-sm fw-bold px-3 shadow-sm"
                            onClick={() => navigate('/owner/bulk-billing')}
                          >
                            <i className="bi bi-cloud-arrow-up me-1"></i> Go to Bulk Billing Import
                          </button>
                        )}
                      </div>
                    </td>
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
