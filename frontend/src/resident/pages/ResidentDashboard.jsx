import React, { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import {
  ResponsiveContainer,
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend
} from 'recharts';
import API from '../../shared/services/api';
import StatCard from '../../shared/components/StatCard';
import WaterSavingTips from '../../shared/components/WaterSavingTips';
import LoadingSpinner from '../../shared/components/LoadingSpinner';
import WaterPredictionCard from '../components/WaterPredictionCard';

export default function ResidentDashboard() {
  const [stats, setStats] = useState(null);
  const [currentOccupancy, setCurrentOccupancy] = useState(null);
  const [pendingRequest, setPendingRequest] = useState(null);
  const [usageList, setUsageList] = useState([]);
  const [loading, setLoading] = useState(true);

  // Guided Apartment Selection Flow State
  const [onboardingStep, setOnboardingStep] = useState(1); // 1: Owners, 2: Buildings, 3: Rooms, 4: Confirm
  const [ownersList, setOwnersList] = useState([]);
  const [selectedOwner, setSelectedOwner] = useState(null);
  const [buildingsList, setBuildingsList] = useState([]);
  const [selectedBuilding, setSelectedBuilding] = useState(null);
  const [availableApartments, setAvailableApartments] = useState([]);
  const [selectedFloor, setSelectedFloor] = useState('ALL');
  const [selectedApartment, setSelectedApartment] = useState(null);
  const [requestNotes, setRequestNotes] = useState('');
  const [loadingStepData, setLoadingStepData] = useState(false);
  const [submittingRequest, setSubmittingRequest] = useState(false);
  const [selectionError, setSelectionError] = useState('');

  // Cancel Request State
  const [cancellingRequest, setCancellingRequest] = useState(false);

  // Move-Out Modal State
  const [showMoveOutModal, setShowMoveOutModal] = useState(false);
  const [moveOutReason, setMoveOutReason] = useState('Relocation');
  const [moveOutConfirm, setMoveOutConfirm] = useState(false);
  const [moveOutSubmitting, setMoveOutSubmitting] = useState(false);
  const [moveOutError, setMoveOutError] = useState('');
  const [pendingBillsWarning, setPendingBillsWarning] = useState(null); // { count: number, total: number }

  // Toast / Status Message
  const [statusAlert, setStatusAlert] = useState(null); // { type: 'success' | 'info' | 'danger', message: '' }

  const loadDashboardData = async () => {
    try {
      const [statRes, usageRes, occRes, pendingRes] = await Promise.all([
        API.get('/resident/dashboard').catch(() => ({ data: null })),
        API.get('/resident/usage').catch(() => ({ data: [] })),
        API.get('/resident/occupancy/current').catch(() => ({ data: null })),
        API.get('/resident/occupancy-request/pending').catch(() => ({ data: null }))
      ]);

      setStats(statRes.data);
      setCurrentOccupancy(occRes.data);
      setPendingRequest(pendingRes.data);

      const rawUsage = usageRes.data || [];
      const sorted = [...rawUsage].sort((a, b) => (a.year - b.year) || (a.month - b.month));
      setUsageList(sorted.length ? sorted : rawUsage);

      // If resident has neither active occupancy nor pending request, initialize step 1 (Owners)
      if (!occRes.data && !pendingRes.data) {
        loadOwners();
      }
    } catch (err) {
      console.error('Failed to load resident dashboard data:', err);
    } finally {
      setLoading(false);
    }
  };

  const loadOwners = async () => {
    setLoadingStepData(true);
    setSelectionError('');
    try {
      const res = await API.get('/apartments/owners');
      setOwnersList(res.data || []);
      setOnboardingStep(1);
    } catch (err) {
      console.error('Failed to load property owners:', err);
      setSelectionError('Unable to load property owners at this time.');
    } finally {
      setLoadingStepData(false);
    }
  };

  const handleSelectOwner = async (owner) => {
    setSelectedOwner(owner);
    setSelectedBuilding(null);
    setSelectedApartment(null);
    setLoadingStepData(true);
    setSelectionError('');
    try {
      const res = await API.get(`/apartments/owners/${owner.ownerId}/buildings`);
      setBuildingsList(res.data || []);
      setOnboardingStep(2);
    } catch (err) {
      console.error('Failed to load buildings for owner:', err);
      setSelectionError('Failed to load properties for this owner.');
    } finally {
      setLoadingStepData(false);
    }
  };

  const handleSelectBuilding = async (building) => {
    setSelectedBuilding(building);
    setSelectedApartment(null);
    setSelectedFloor('ALL');
    setLoadingStepData(true);
    setSelectionError('');
    try {
      const res = await API.get('/apartments/available', {
        params: { buildingId: building.buildingId }
      });
      setAvailableApartments(res.data || []);
      setOnboardingStep(3);
    } catch (err) {
      console.error('Failed to load apartments for building:', err);
      setSelectionError('Failed to load available apartments for this property.');
    } finally {
      setLoadingStepData(false);
    }
  };

  const handleSelectApartment = (apt) => {
    setSelectedApartment(apt);
    setOnboardingStep(4);
  };

  const handleSubmitOccupancyRequest = async (e) => {
    e.preventDefault();
    if (!selectedApartment) return;

    setSubmittingRequest(true);
    setSelectionError('');
    try {
      const res = await API.post('/resident/occupancy-request', {
        apartmentId: selectedApartment.apartmentId,
        notes: requestNotes.trim() || null
      });

      setStatusAlert({
        type: 'success',
        message: `✓ Occupancy request submitted for Unit ${res.data?.roomNumber} in ${res.data?.buildingName}! The building owner has been notified and will review your request.`
      });

      // Reload dashboard state to show Pending Request view
      await loadDashboardData();
    } catch (err) {
      setSelectionError(err.response?.data?.message || 'Failed to submit occupancy request. The unit may have just been requested by someone else.');
    } finally {
      setSubmittingRequest(false);
    }
  };

  const handleCancelPendingRequest = async () => {
    if (!pendingRequest) return;
    if (!window.confirm(`Cancel your pending occupancy request for Unit ${pendingRequest.roomNumber} (${pendingRequest.buildingName})?`)) {
      return;
    }

    setCancellingRequest(true);
    try {
      await API.post(`/resident/occupancy-request/${pendingRequest.requestId}/cancel`);
      setStatusAlert({
        type: 'info',
        message: '✓ Your occupancy request has been cancelled. You may now select another property or unit.'
      });
      setPendingRequest(null);
      await loadDashboardData();
    } catch (err) {
      alert(err.response?.data?.message || 'Failed to cancel request.');
    } finally {
      setCancellingRequest(false);
    }
  };

  const handleOpenMoveOut = async () => {
    setMoveOutConfirm(false);
    setMoveOutError('');
    setPendingBillsWarning(null);
    setShowMoveOutModal(true);

    try {
      const billsRes = await API.get('/resident/bills');
      const unpaid = (billsRes.data || []).filter(b => b.status !== 'PAID');
      if (unpaid.length > 0) {
        const totalDue = unpaid.reduce((sum, b) => sum + (b.amount || 0), 0);
        setPendingBillsWarning({
          count: unpaid.length,
          total: totalDue
        });
      }
    } catch (err) {
      console.error('Failed to verify resident bills before move-out:', err);
    }
  };

  const handleMoveOutSubmit = async (e) => {
    e.preventDefault();
    if (!moveOutConfirm) {
      setMoveOutError('Please confirm the agreement checkbox to proceed.');
      return;
    }

    setMoveOutSubmitting(true);
    setMoveOutError('');

    try {
      const res = await API.post('/resident/move-out', {
        reason: moveOutReason,
        confirm: true
      });
      setShowMoveOutModal(false);
      setStatusAlert({
        type: 'info',
        message: `✓ Move-out completed for Unit ${res.data?.roomNumber} (${res.data?.buildingName}). The unit has been vacated for future occupants. Your billing and payment history remain securely preserved.`
      });
      await loadDashboardData();
    } catch (err) {
      setMoveOutError(err.response?.data?.message || 'Failed to complete move-out request.');
    } finally {
      setMoveOutSubmitting(false);
    }
  };

  useEffect(() => {
    loadDashboardData();
  }, []);

  if (loading) return <LoadingSpinner text="Loading Resident Dashboard..." />;

  const hasActiveApartment = currentOccupancy && currentOccupancy.status === 'ACTIVE';
  const hasPendingRequest = pendingRequest && pendingRequest.status === 'PENDING';

  // Distinct floors for step 3 filter
  const distinctFloors = Array.from(new Set(availableApartments.map(a => a.floorNumber))).sort((a, b) => a - b);
  const filteredApartments = availableApartments.filter(a =>
    selectedFloor === 'ALL' || a.floorNumber === parseInt(selectedFloor)
  );

  // Prepare Recharts Data
  const chartData = usageList.map(u => ({
    label: `${u.month}/${u.year}`,
    consumption: u.unitsConsumed || 0,
    reading: u.currentReading || 0
  }));

  return (
    <div className="container-fluid py-4">
      {/* Toast Alert Banner */}
      {statusAlert && (
        <div className={`alert alert-${statusAlert.type} alert-dismissible fade show shadow-sm rounded-4 mb-4`} role="alert">
          <div className="d-flex align-items-center gap-2">
            <i className={`bi ${statusAlert.type === 'success' ? 'bi-check-circle-fill' : 'bi-info-circle-fill'} fs-5`}></i>
            <div className="fw-semibold text-dark">{statusAlert.message}</div>
          </div>
          <button type="button" className="btn-close" onClick={() => setStatusAlert(null)}></button>
        </div>
      )}

      {/* ========================================================================= */}
      {/* SCENARIO 1: ACTIVE OCCUPANT DASHBOARD */}
      {/* ========================================================================= */}
      {hasActiveApartment ? (
        <>
          {/* Active Resident Hero Card */}
          <div className="card glass-card border-0 shadow-sm p-4 mb-4 bg-white rounded-4">
            <div className="d-flex align-items-center justify-content-between flex-wrap gap-3">
              <div>
                <div className="d-flex align-items-center gap-2 mb-1">
                  <h2 className="fw-bold text-dark mb-0">Welcome, {stats?.residentName || 'Resident'}</h2>
                  <span className="badge bg-success-subtle text-success border border-success-subtle px-2.5 py-1 fs-8 fw-bold rounded-pill">
                    <i className="bi bi-check-circle-fill me-1"></i> Active Occupant
                  </span>
                </div>
                <p className="text-muted small mb-0">
                  <i className="bi bi-building me-1 text-primary"></i> <strong>{currentOccupancy?.buildingName}</strong>
                  {currentOccupancy?.floorNumber && <span> • Floor {currentOccupancy.floorNumber}</span>}
                  <span> • <i className="bi bi-door-open me-1 text-success"></i> Apt <strong>{currentOccupancy?.roomNumber}</strong></span>
                  {currentOccupancy?.unitType && <span className="badge bg-light text-dark ms-2 border">{currentOccupancy.unitType}</span>}
                  <span> • Meter: <code className="text-dark fw-bold">{currentOccupancy?.meterNumber || 'WM-Fixture'}</code></span>
                  {currentOccupancy?.ownerName && <span className="text-muted ms-2">(Owner: {currentOccupancy.ownerName})</span>}
                </p>
              </div>

              <div className="d-flex align-items-center gap-2 flex-wrap">
                <Link to="/resident/comparison" className="btn btn-outline-dark fw-semibold">
                  <i className="bi bi-bar-chart-line me-1.5 text-primary"></i> Compare Usage
                </Link>
                <Link to="/resident/bills" className="btn btn-dark fw-bold">
                  <i className="bi bi-wallet2 me-1.5 text-info"></i> View Bills
                </Link>
                <button
                  className="btn btn-outline-danger fw-semibold"
                  onClick={handleOpenMoveOut}
                  title="End active occupancy and vacate unit"
                >
                  <i className="bi bi-box-arrow-right me-1.5"></i> Move Out
                </button>
              </div>
            </div>
          </div>

          {/* Telemetry Stat Cards */}
          <div className="row g-3 mb-4">
            <div className="col-md-4">
              <StatCard title="Current Consumption" value={`${stats?.currentMonthUsage || 0} kL`} icon="bi-droplet-half" subtext={`Cycle: ${stats?.currentBillingCycle || 'Active'}`} />
            </div>
            <div className="col-md-4">
              <StatCard title="Current Bill Amount" value={`₹${(stats?.activeBillAmount || 0).toFixed(2)}`} icon="bi-cash" subtext={`Due: ${stats?.activeBillDueDate || 'N/A'}`} />
            </div>
            <div className="col-md-4">
              <StatCard title="Payment Status" value={stats?.activeBillStatus || 'N/A'} icon="bi-check-circle" />
            </div>
          </div>

          {/* Line Chart & Billing Cycle Details */}
          <div className="row g-4 mb-4">
            <div className="col-lg-8">
              <div className="card glass-card border shadow-sm p-4 h-100 bg-white rounded-4">
                <div className="d-flex align-items-center justify-content-between mb-3 flex-wrap gap-2">
                  <div>
                    <h5 className="fw-bold mb-1 text-dark">Monthly Consumption Trend</h5>
                    <p className="text-muted fs-7 mb-0">Historical water usage trend across billing cycles (kL)</p>
                  </div>
                  <span className="badge bg-light text-dark border px-3 py-1.5 fs-8 fw-semibold">
                    <i className="bi bi-graph-up-arrow text-primary me-1.5"></i> Live Telemetry
                  </span>
                </div>

                {chartData.length ? (
                  <div style={{ width: '100%', height: 280 }} className="pt-2">
                    <ResponsiveContainer width="100%" height="100%">
                      <LineChart data={chartData} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
                        <CartesianGrid strokeDasharray="3 3" stroke="#F1F5F9" />
                        <XAxis dataKey="label" stroke="#64748B" fontSize={12} tickLine={false} />
                        <YAxis stroke="#64748B" fontSize={12} tickLine={false} unit=" kL" />
                        <Tooltip
                          contentStyle={{ backgroundColor: '#003554', borderRadius: '8px', color: '#fff', border: 'none' }}
                          itemStyle={{ color: '#E3F6FF', fontWeight: 'bold' }}
                          formatter={(val) => [`${val} kL`, 'Consumption']}
                          labelFormatter={(lbl) => `Billing Month: ${lbl}`}
                        />
                        <Legend wrapperStyle={{ fontSize: '12px', paddingTop: '10px' }} />
                        <Line
                          type="monotone"
                          dataKey="consumption"
                          name="Water Consumption (kL)"
                          stroke="#005F73"
                          strokeWidth={3}
                          activeDot={{ r: 8, fill: '#0A9396' }}
                        />
                      </LineChart>
                    </ResponsiveContainer>
                  </div>
                ) : (
                  <div className="p-5 text-center bg-light rounded-3 border my-auto">
                    <i className="bi bi-droplet text-secondary display-4 d-block mb-2"></i>
                    <h5 className="fw-bold text-dark">No Consumption Logs Available</h5>
                    <small className="text-muted">
                      Meter readings will appear here after the upcoming billing cycle is processed.
                    </small>
                  </div>
                )}
              </div>
            </div>

            <div className="col-lg-4">
              <div className="card glass-card border shadow-sm p-4 h-100 bg-white rounded-4">
                <h5 className="fw-bold mb-3 text-dark">Current Billing Cycle</h5>
                <div className="p-3 bg-light rounded-3 border mb-3">
                  <div className="mb-2">
                    <small className="text-muted fw-semibold text-uppercase fs-8 d-block">Billing Cycle</small>
                    <strong className="text-dark fs-6">{stats?.currentBillingCycle || 'Active Cycle'}</strong>
                  </div>
                  <div className="row g-2 mb-2 pt-2 border-top">
                    <div className="col-6">
                      <small className="text-muted fs-8 d-block">Start Date</small>
                      <span className="fw-semibold text-dark fs-7">{stats?.cycleStartDate || 'N/A'}</span>
                    </div>
                    <div className="col-6">
                      <small className="text-muted fs-8 d-block">End Date</small>
                      <span className="fw-semibold text-dark fs-7">{stats?.cycleEndDate || 'N/A'}</span>
                    </div>
                  </div>
                  <div className="pt-2 border-top">
                    <small className="text-muted fw-semibold text-uppercase fs-8 d-block">Current Consumption</small>
                    <h3 className="fw-bold text-dark mb-2">{stats?.currentMonthUsage || 0} kL</h3>
                    <small className="text-muted fw-semibold text-uppercase fs-8 d-block">Current Bill Amount</small>
                    <h2 className="fw-extrabold text-dark mb-1">₹{(stats?.activeBillAmount || 0).toFixed(2)}</h2>
                  </div>
                  <div className="d-flex align-items-center justify-content-between mt-2 pt-2 border-top fs-8">
                    <span className="text-muted">Due Date:</span>
                    <strong className="text-dark">{stats?.activeBillDueDate || 'N/A'}</strong>
                  </div>
                  <div className="d-flex align-items-center justify-content-between mt-1 fs-8">
                    <span className="text-muted">Payment Status:</span>
                    <span className={`badge ${stats?.activeBillStatus === 'PAID' ? 'bg-success-subtle text-success border border-success-subtle' : 'bg-warning-subtle text-dark border'}`}>
                      {stats?.activeBillStatus || 'UNPAID'}
                    </span>
                  </div>
                </div>

                <div className="d-grid gap-2">
                  <Link to="/resident/bills" className="btn btn-dark fw-bold py-2">
                    <i className="bi bi-receipt me-1.5"></i> Open Billing Statement
                  </Link>
                  <Link to="/resident/notifications" className="btn btn-outline-secondary py-2 fs-7 fw-semibold">
                    <i className="bi bi-bell me-1.5"></i> Notifications ({stats?.unreadNotificationsCount || 0})
                  </Link>
                </div>
              </div>
            </div>
          </div>

          <WaterPredictionCard />

          <div className="mb-4">
            <WaterSavingTips />
          </div>
        </>
      ) : hasPendingRequest ? (
        /* ========================================================================= */
        /* SCENARIO 2: OCCUPANCY REQUEST PENDING APPROVAL */
        /* ========================================================================= */
        <div className="row justify-content-center py-4">
          <div className="col-lg-8">
            <div className="card glass-card border-0 shadow-lg p-4 p-md-5 rounded-4 bg-white text-center">
              {/* Animated / Pulsing Hourglass Icon */}
              <div
                className="d-inline-flex align-items-center justify-content-center rounded-circle mx-auto mb-3 shadow-sm"
                style={{ width: 72, height: 72, backgroundColor: '#FEF3C7', color: '#D97706' }}
              >
                <i className="bi bi-hourglass-split display-5"></i>
              </div>

              <span className="badge bg-warning-subtle text-warning-emphasis border border-warning px-3 py-1.5 fs-7 fw-bold rounded-pill mx-auto mb-2">
                Occupancy Request Pending Owner Review
              </span>

              <h3 className="fw-bold text-dark mb-2">Your Occupancy Request Is Under Review</h3>
              <p className="text-muted fs-7 mx-auto mb-4" style={{ maxWidth: 580 }}>
                You have requested to occupy <strong>Unit {pendingRequest.roomNumber}</strong> in <strong>{pendingRequest.buildingName}</strong>. 
                The property owner ({pendingRequest.ownerName || 'Building Owner'}) has received your application. Once approved, live telemetry and water billing for this unit will automatically activate.
              </p>

              {/* Request Details Card */}
              <div className="card bg-light border rounded-4 p-4 text-start mb-4 shadow-none">
                <div className="d-flex align-items-center justify-content-between mb-3 border-bottom pb-2">
                  <h6 className="fw-bold text-dark mb-0">Application Summary</h6>
                  <span className="text-muted fs-8">Request #{pendingRequest.requestId}</span>
                </div>

                <div className="row g-3 fs-7">
                  <div className="col-sm-6">
                    <small className="text-muted d-block fs-8">Property / Building</small>
                    <strong className="text-dark">{pendingRequest.buildingName}</strong>
                    <div className="text-muted fs-8">{pendingRequest.buildingLocation}</div>
                  </div>
                  <div className="col-sm-6">
                    <small className="text-muted d-block fs-8">Requested Unit</small>
                    <strong className="text-dark">Room {pendingRequest.roomNumber} (Floor {pendingRequest.floorNumber})</strong>
                    <div className="text-muted fs-8">{pendingRequest.unitType || 'Apartment'}</div>
                  </div>
                  <div className="col-sm-6">
                    <small className="text-muted d-block fs-8">Assigned Water Meter</small>
                    <code className="text-dark fw-bold fs-7">{pendingRequest.meterNumber || 'Assigned on approval'}</code>
                  </div>
                  <div className="col-sm-6">
                    <small className="text-muted d-block fs-8">Submission Date</small>
                    <span className="fw-semibold text-dark">
                      {pendingRequest.requestDate ? new Date(pendingRequest.requestDate).toLocaleString() : 'Recent'}
                    </span>
                  </div>
                  {pendingRequest.notes && (
                    <div className="col-12 pt-2 border-top">
                      <small className="text-muted d-block fs-8">Your Submitted Note</small>
                      <span className="fst-italic text-dark">"{pendingRequest.notes}"</span>
                    </div>
                  )}
                </div>
              </div>

              {/* Action Buttons */}
              <div className="d-flex align-items-center justify-content-center gap-3">
                <button
                  className="btn btn-outline-danger fw-bold px-4 py-2"
                  onClick={handleCancelPendingRequest}
                  disabled={cancellingRequest}
                >
                  {cancellingRequest ? (
                    <span><span className="spinner-border spinner-border-sm me-1.5"></span>Cancelling...</span>
                  ) : (
                    <span><i className="bi bi-x-circle me-1.5"></i> Cancel This Request</span>
                  )}
                </button>
                <button
                  className="btn btn-dark fw-bold px-4 py-2"
                  onClick={loadDashboardData}
                >
                  <i className="bi bi-arrow-clockwise me-1.5"></i> Refresh Status
                </button>
              </div>
            </div>
          </div>
        </div>
      ) : (
        /* ========================================================================= */
        /* SCENARIO 3: UNASSIGNED RESIDENT - GUIDED APARTMENT ONBOARDING FLOW */
        /* ========================================================================= */
        <div className="row justify-content-center py-2">
          <div className="col-lg-10">
            {/* Guided Flow Card */}
            <div className="card glass-card border-0 shadow-lg p-4 p-md-5 rounded-4 bg-white mb-4">
              {/* Header */}
              <div className="text-center mb-4">
                <div
                  className="d-inline-flex align-items-center justify-content-center rounded-circle mx-auto mb-2.5 shadow-sm"
                  style={{ width: 60, height: 60, backgroundColor: '#005F73', color: '#fff' }}
                >
                  <i className="bi bi-door-open-fill fs-3"></i>
                </div>
                <h3 className="fw-bold text-dark mb-1">Select Your Apartment Unit</h3>
                <p className="text-muted fs-7 mx-auto mb-0" style={{ maxWidth: 640 }}>
                  Welcome to AquaFlow! Follow the guided steps below to select your property owner, choose an available building, and request occupancy for your room.
                </p>
              </div>

              {/* Step Progress Tracker */}
              <div className="d-flex align-items-center justify-content-center mb-4 pb-3 border-bottom flex-wrap gap-2 gap-md-4">
                {[
                  { num: 1, title: 'Select Owner', icon: 'bi-person-badge' },
                  { num: 2, title: 'Select Building', icon: 'bi-building' },
                  { num: 3, title: 'Choose Room', icon: 'bi-door-open' },
                  { num: 4, title: 'Confirm & Submit', icon: 'bi-check2-circle' }
                ].map(s => {
                  const isActive = onboardingStep === s.num;
                  const isDone = onboardingStep > s.num;
                  return (
                    <div key={s.num} className="d-flex align-items-center gap-2">
                      <div
                        className={`rounded-circle d-flex align-items-center justify-content-center fw-bold fs-7 transition-all ${
                          isActive
                            ? 'bg-dark text-white shadow-sm'
                            : isDone
                            ? 'bg-success text-white'
                            : 'bg-light text-muted border'
                        }`}
                        style={{ width: 34, height: 34 }}
                      >
                        {isDone ? <i className="bi bi-check"></i> : s.num}
                      </div>
                      <span className={`fs-7 fw-semibold ${isActive ? 'text-dark' : isDone ? 'text-success' : 'text-muted'}`}>
                        {s.title}
                      </span>
                      {s.num < 4 && <i className="bi bi-chevron-right text-muted fs-9 d-none d-md-inline ms-1"></i>}
                    </div>
                  );
                })}
              </div>

              {/* Error Banner */}
              {selectionError && (
                <div className="alert alert-danger py-2 px-3 small rounded-3 mb-4 d-flex align-items-center gap-2">
                  <i className="bi bi-exclamation-triangle-fill fs-6"></i>
                  <div>{selectionError}</div>
                </div>
              )}

              {loadingStepData ? (
                <div className="py-5 text-center">
                  <div className="spinner-border text-primary mb-2" role="status"></div>
                  <div className="text-muted fs-7">Loading property inventory...</div>
                </div>
              ) : (
                <>
                  {/* STEP 1: SELECT BUILDING OWNER */}
                  {onboardingStep === 1 && (
                    <div>
                      <div className="d-flex align-items-center justify-content-between mb-3">
                        <h5 className="fw-bold text-dark mb-0">Step 1: Choose Property Owner</h5>
                        <span className="text-muted fs-8">{ownersList.length} verified owner(s)</span>
                      </div>

                      {ownersList.length === 0 ? (
                        <div className="text-center py-5 bg-light rounded-4 border">
                          <i className="bi bi-building-slash text-muted display-4 d-block mb-2"></i>
                          <h6 className="fw-bold text-dark">No Registered Property Owners</h6>
                          <p className="text-muted fs-8 mb-0" style={{ maxWidth: 480, margin: '0 auto' }}>
                            No approved building owners are registered in the system yet. Once an owner registers their property and is approved by the Super Admin, their units will appear here for occupancy requests.
                          </p>
                        </div>
                      ) : (
                        <div className="row g-3">
                          {ownersList.map(owner => (
                            <div className="col-md-6" key={owner.ownerId}>
                              <div
                                className="card h-100 p-3 border rounded-4 hover-shadow transition-all cursor-pointer bg-light"
                                onClick={() => handleSelectOwner(owner)}
                                style={{ cursor: 'pointer' }}
                              >
                                <div className="d-flex align-items-center justify-content-between mb-2">
                                  <div className="d-flex align-items-center gap-2.5">
                                    <div
                                      className="rounded-circle d-flex align-items-center justify-content-center text-white fw-bold fs-6"
                                      style={{ width: 44, height: 44, backgroundColor: '#005F73' }}
                                    >
                                      {owner.name ? owner.name.charAt(0).toUpperCase() : 'O'}
                                    </div>
                                    <div>
                                      <h6 className="fw-bold text-dark mb-0">{owner.name}</h6>
                                      {owner.phone && (
                                        <small className="text-muted fs-8">
                                          <i className="bi bi-telephone me-1"></i>{owner.phone}
                                        </small>
                                      )}
                                    </div>
                                  </div>
                                  <span className="badge bg-primary-subtle text-primary border border-primary-subtle fs-8">
                                    {owner.totalBuildings || 1} {owner.totalBuildings === 1 ? 'Property' : 'Properties'}
                                  </span>
                                </div>

                                <div className="mt-auto pt-2 border-top d-flex align-items-center justify-content-between">
                                  <span className="text-muted fs-8">Click to view buildings</span>
                                  <span className="fw-bold text-dark fs-8">
                                    Select Owner <i className="bi bi-arrow-right ms-1"></i>
                                  </span>
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )}

                  {/* STEP 2: SELECT BUILDING PROPERTY */}
                  {onboardingStep === 2 && (
                    <div>
                      <div className="d-flex align-items-center justify-content-between mb-3 flex-wrap gap-2">
                        <div>
                          <h5 className="fw-bold text-dark mb-0">Step 2: Choose Building Property</h5>
                          <small className="text-muted">Owner: <strong>{selectedOwner?.name}</strong></small>
                        </div>
                        <button className="btn btn-sm btn-outline-secondary" onClick={() => setOnboardingStep(1)}>
                          <i className="bi bi-arrow-left me-1"></i> Back to Owners
                        </button>
                      </div>

                      {buildingsList.length === 0 ? (
                        <div className="text-center py-5 bg-light rounded-4 border">
                          <i className="bi bi-building-slash text-muted display-4 d-block mb-2"></i>
                          <h6 className="fw-bold text-dark">No Buildings Found</h6>
                          <p className="text-muted fs-8 mb-3">This property owner does not have any active buildings registered yet.</p>
                          <button className="btn btn-sm btn-dark" onClick={() => setOnboardingStep(1)}>
                            <i className="bi bi-arrow-left me-1"></i> Choose Another Owner
                          </button>
                        </div>
                      ) : (
                        <div className="row g-3">
                          {buildingsList.map(b => (
                            <div className="col-md-6" key={b.buildingId}>
                              <div
                                className="card h-100 p-3 border rounded-4 hover-shadow transition-all cursor-pointer bg-light"
                                onClick={() => handleSelectBuilding(b)}
                                style={{ cursor: 'pointer' }}
                              >
                                <div className="d-flex align-items-center justify-content-between mb-2">
                                  <div className="d-flex align-items-center gap-2">
                                    <div className="rounded-3 bg-white border p-2 text-dark fs-5">
                                      <i className="bi bi-building"></i>
                                    </div>
                                    <div>
                                      <h6 className="fw-bold text-dark mb-0">{b.buildingName}</h6>
                                      <small className="text-muted fs-8">{b.buildingNumber}</small>
                                    </div>
                                  </div>
                                  <span className={`badge ${b.vacantCount > 0 ? 'bg-success-subtle text-success border border-success-subtle' : 'bg-secondary-subtle text-muted border'} fs-8`}>
                                    {b.vacantCount > 0 ? `${b.vacantCount} Vacant Units` : 'Check Units'}
                                  </span>
                                </div>

                                <div className="text-muted fs-8 mb-2">
                                  <i className="bi bi-geo-alt me-1"></i> {b.location || 'Bangalore'}
                                </div>

                                <div className="mt-auto pt-2 border-top d-flex align-items-center justify-content-between">
                                  <span className="text-muted fs-8">Total Planned Flats: {b.totalFlats || 10}</span>
                                  <span className="fw-bold text-dark fs-8">
                                    Choose Building <i className="bi bi-arrow-right ms-1"></i>
                                  </span>
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )}

                  {/* STEP 3: CHOOSE FLOOR & AVAILABLE ROOM */}
                  {onboardingStep === 3 && (
                    <div>
                      <div className="d-flex align-items-center justify-content-between mb-3 flex-wrap gap-2">
                        <div>
                          <h5 className="fw-bold text-dark mb-0">Step 3: Select Floor & Room Number</h5>
                          <small className="text-muted">
                            Property: <strong>{selectedBuilding?.buildingName}</strong> ({selectedBuilding?.location})
                          </small>
                        </div>
                        <button className="btn btn-sm btn-outline-secondary" onClick={() => setOnboardingStep(2)}>
                          <i className="bi bi-arrow-left me-1"></i> Back to Buildings
                        </button>
                      </div>

                      {/* Floor Filter Tabs */}
                      {distinctFloors.length > 1 && (
                        <div className="d-flex align-items-center gap-2 mb-3 flex-wrap">
                          <span className="fs-8 fw-semibold text-muted">Filter by Floor:</span>
                          <button
                            className={`btn btn-sm rounded-pill px-3 fw-semibold ${selectedFloor === 'ALL' ? 'btn-dark' : 'btn-outline-secondary bg-light'}`}
                            onClick={() => setSelectedFloor('ALL')}
                          >
                            All Floors
                          </button>
                          {distinctFloors.map(f => (
                            <button
                              key={f}
                              className={`btn btn-sm rounded-pill px-3 fw-semibold ${selectedFloor === f.toString() ? 'btn-dark' : 'btn-outline-secondary bg-light'}`}
                              onClick={() => setSelectedFloor(f.toString())}
                            >
                              Floor {f}
                            </button>
                          ))}
                        </div>
                      )}

                      {filteredApartments.length === 0 ? (
                        <div className="text-center py-5 bg-light rounded-4 border">
                          <i className="bi bi-door-closed text-muted display-4 d-block mb-2"></i>
                          <h6 className="fw-bold text-dark">No Vacant Units Found</h6>
                          <p className="text-muted fs-8 mb-3">All units in this building are currently occupied or under review.</p>
                          <button className="btn btn-sm btn-dark" onClick={() => setOnboardingStep(2)}>
                            Choose Another Building
                          </button>
                        </div>
                      ) : (
                        <div className="row g-3" style={{ maxHeight: 420, overflowY: 'auto' }}>
                          {filteredApartments.map(apt => (
                            <div className="col-sm-6 col-md-4" key={apt.apartmentId}>
                              <div
                                className="card h-100 p-3 border rounded-4 hover-shadow transition-all cursor-pointer bg-white"
                                onClick={() => handleSelectApartment(apt)}
                                style={{ cursor: 'pointer', border: '1px solid #D1EBF8' }}
                              >
                                <div className="d-flex align-items-center justify-content-between mb-2">
                                  <span className="badge bg-success-subtle text-success border border-success-subtle fs-8 fw-bold">
                                    AVAILABLE
                                  </span>
                                  <span className="badge bg-light text-dark border fs-8">
                                    Floor {apt.floorNumber}
                                  </span>
                                </div>

                                <h5 className="fw-bold text-dark mb-1">
                                  Room {apt.roomNumber}
                                </h5>
                                <div className="text-muted fs-8 mb-2">
                                  {apt.apartmentName ? `${apt.apartmentName} • ` : ''}{apt.unitType || '2BHK'}
                                </div>

                                <div className="mt-auto pt-2 border-top d-flex align-items-center justify-content-between">
                                  <span className="text-muted fs-8">
                                    Meter: <code>{apt.meterNumber || 'Pending'}</code>
                                  </span>
                                  <span className="fw-bold text-primary fs-8">
                                    Select <i className="bi bi-chevron-right ms-1"></i>
                                  </span>
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )}

                  {/* STEP 4: CONFIRM & SUBMIT OCCUPANCY REQUEST */}
                  {onboardingStep === 4 && selectedApartment && (
                    <div>
                      <div className="d-flex align-items-center justify-content-between mb-3 flex-wrap gap-2">
                        <h5 className="fw-bold text-dark mb-0">Step 4: Review & Submit Occupancy Request</h5>
                        <button className="btn btn-sm btn-outline-secondary" onClick={() => setOnboardingStep(3)}>
                          <i className="bi bi-arrow-left me-1"></i> Back to Room Selection
                        </button>
                      </div>

                      <form onSubmit={handleSubmitOccupancyRequest}>
                        {/* Summary Details Box */}
                        <div className="card bg-light border rounded-4 p-4 mb-3 shadow-none">
                          <h6 className="fw-bold text-dark mb-3 border-bottom pb-2">Selected Unit Details</h6>
                          <div className="row g-3 fs-7">
                            <div className="col-sm-6">
                              <small className="text-muted d-block fs-8">Property Owner</small>
                              <strong className="text-dark">{selectedOwner?.name}</strong>
                              {selectedOwner?.phone && <div className="text-muted fs-8">{selectedOwner.phone}</div>}
                            </div>
                            <div className="col-sm-6">
                              <small className="text-muted d-block fs-8">Building Property</small>
                              <strong className="text-dark">{selectedBuilding?.buildingName}</strong>
                              <div className="text-muted fs-8">{selectedBuilding?.location}</div>
                            </div>
                            <div className="col-sm-6">
                              <small className="text-muted d-block fs-8">Chosen Apartment Room</small>
                              <strong className="text-dark fs-6 text-primary">
                                Room {selectedApartment.roomNumber} (Floor {selectedApartment.floorNumber})
                              </strong>
                              <div className="text-muted fs-8">{selectedApartment.unitType || 'Standard Flat'}</div>
                            </div>
                            <div className="col-sm-6">
                              <small className="text-muted d-block fs-8">Assigned Water Meter Fixture</small>
                              <code className="text-dark fw-bold fs-7">{selectedApartment.meterNumber || 'WM-Fixture'}</code>
                            </div>
                          </div>
                        </div>

                        {/* Optional Notes Textarea */}
                        <div className="mb-4">
                          <label className="form-label fw-semibold fs-7 text-dark">
                            Message / Move-in Note for Owner (Optional)
                          </label>
                          <textarea
                            className="form-control"
                            rows={3}
                            placeholder="e.g. Hi, I would like to move in starting next Monday. All my verification documents are ready."
                            value={requestNotes}
                            onChange={e => setRequestNotes(e.target.value)}
                          ></textarea>
                          <small className="text-muted fs-8">
                            This message will be visible to {selectedOwner?.name} during application review.
                          </small>
                        </div>

                        {/* Informational Alert */}
                        <div className="alert alert-info py-2.5 px-3 fs-8 rounded-3 mb-4 d-flex align-items-center gap-2">
                          <i className="bi bi-info-circle-fill fs-6 text-primary"></i>
                          <div>
                            Submitting this request reserves the unit while under review. Once the owner approves your application, this apartment and its smart water meter telemetry will be activated in your dashboard.
                          </div>
                        </div>

                        <div className="d-flex align-items-center justify-content-end gap-2">
                          <button
                            type="button"
                            className="btn btn-outline-secondary px-4 py-2"
                            onClick={() => setOnboardingStep(3)}
                            disabled={submittingRequest}
                          >
                            Back
                          </button>
                          <button
                            type="submit"
                            className="btn btn-dark fw-bold px-4 py-2 shadow-sm"
                            disabled={submittingRequest}
                          >
                            {submittingRequest ? (
                              <span><span className="spinner-border spinner-border-sm me-1.5"></span>Submitting Application...</span>
                            ) : (
                              <span><i className="bi bi-send-fill me-1.5"></i> Submit Occupancy Request</span>
                            )}
                          </button>
                        </div>
                      </form>
                    </div>
                  )}
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL: MOVE-OUT / VACATE APARTMENT CONFIRMATION */}
      {/* ========================================================================= */}
      {showMoveOutModal && (
        <div className="modal show d-block" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content border-0 shadow-lg rounded-4">
              <div className="modal-header border-0 pb-0">
                <div>
                  <h5 className="modal-title fw-bold text-danger">
                    <i className="bi bi-box-arrow-right me-2"></i>Confirm Move-Out / Vacate Unit
                  </h5>
                  <p className="text-muted fs-8 mb-0">End your active occupancy and release the apartment</p>
                </div>
                <button type="button" className="btn-close" onClick={() => setShowMoveOutModal(false)}></button>
              </div>

              <form onSubmit={handleMoveOutSubmit}>
                <div className="modal-body">
                  {moveOutError && (
                    <div className="alert alert-danger py-2 px-3 small rounded-3 mb-3">
                      <i className="bi bi-exclamation-triangle-fill me-1"></i> {moveOutError}
                    </div>
                  )}

                  <div className="p-3 bg-light rounded-3 border mb-3">
                    <div className="fs-7 text-dark mb-1">
                      <strong>Current Property:</strong> {currentOccupancy?.buildingName}
                    </div>
                    <div className="fs-7 text-dark mb-1">
                      <strong>Apartment Unit:</strong> Room {currentOccupancy?.roomNumber} (Floor {currentOccupancy?.floorNumber})
                    </div>
                    <div className="fs-7 text-dark">
                      <strong>Move-In Date:</strong> {currentOccupancy?.startDate ? new Date(currentOccupancy.startDate).toLocaleDateString() : 'Active'}
                    </div>
                  </div>

                  {pendingBillsWarning && (
                    <div className="alert alert-danger p-3 rounded-3 mb-3 border-danger shadow-xs">
                      <div className="d-flex align-items-start gap-2">
                        <i className="bi bi-exclamation-octagon-fill fs-5 text-danger flex-shrink-0 mt-0.5"></i>
                        <div>
                          <div className="fw-bold text-danger mb-1">Cannot Vacate: Outstanding Bills Detected</div>
                          <div className="small text-dark mb-2">
                            You currently have <strong>{pendingBillsWarning.count} pending bill(s)</strong> totaling <strong className="text-danger">₹{pendingBillsWarning.total.toFixed(2)}</strong>.
                            You cannot leave the apartment until all outstanding water charges are cleared in full.
                          </div>
                          <Link to="/resident/bills" className="btn btn-sm btn-danger fw-bold" onClick={() => setShowMoveOutModal(false)}>
                            <i className="bi bi-wallet2 me-1"></i> Pay Pending Bills Now
                          </Link>
                        </div>
                      </div>
                    </div>
                  )}

                  <div className="alert alert-warning py-2.5 px-3 fs-8 rounded-3 mb-3">
                    <i className="bi bi-info-circle-fill me-1.5"></i>
                    <strong>What happens when you move out:</strong>
                    <ul className="mb-0 ps-3 mt-1">
                      <li>Your active occupancy in this unit is terminated.</li>
                      <li>The apartment is marked as <strong>Vacant</strong> for future tenants.</li>
                      <li>Live water meter telemetry is detached from your active dashboard.</li>
                      <li>Your account, past payment receipts, and historical invoices remain fully accessible.</li>
                    </ul>
                  </div>

                  <div className="mb-3">
                    <label className="form-label fw-semibold fs-7 text-dark">Reason for Moving Out</label>
                    <select
                      className="form-select form-select-sm"
                      value={moveOutReason}
                      onChange={(e) => setMoveOutReason(e.target.value)}
                      disabled={!!pendingBillsWarning}
                    >
                      <option value="Relocation">Job / City Relocation</option>
                      <option value="Lease Term Expired">Lease Term Expired</option>
                      <option value="Purchased Home">Purchased New Home</option>
                      <option value="Personal Reasons">Personal Reasons</option>
                      <option value="Other">Other</option>
                    </select>
                  </div>

                  <div className="form-check p-3 rounded-3 border" style={{ backgroundColor: '#FFF5F5', borderColor: '#FED7D7' }}>
                    <input
                      className="form-check-input"
                      type="checkbox"
                      id="confirmMoveOutCheck"
                      checked={moveOutConfirm}
                      onChange={(e) => setMoveOutConfirm(e.target.checked)}
                      disabled={!!pendingBillsWarning}
                      required
                    />
                    <label className="form-check-label fs-7 fw-semibold text-danger" htmlFor="confirmMoveOutCheck">
                      I understand that this action vacates Unit {currentOccupancy?.roomNumber} and ends my active occupancy.
                    </label>
                  </div>
                </div>

                <div className="modal-footer border-0">
                  <button type="button" className="btn btn-light" onClick={() => setShowMoveOutModal(false)} disabled={moveOutSubmitting}>
                    Cancel
                  </button>
                  <button type="submit" className="btn btn-danger fw-bold px-3" disabled={moveOutSubmitting || !moveOutConfirm || !!pendingBillsWarning}>
                    {moveOutSubmitting ? (
                      <span><span className="spinner-border spinner-border-sm me-1"></span>Processing...</span>
                    ) : (
                      <span><i className="bi bi-box-arrow-right me-1"></i> Confirm & Vacate Unit</span>
                    )}
                  </button>

                </div>
              </form>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
