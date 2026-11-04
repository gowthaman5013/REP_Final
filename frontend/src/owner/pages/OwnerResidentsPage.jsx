import React, { useState, useEffect } from 'react';
import API from '../../shared/services/api';
import LoadingSpinner from '../../shared/components/LoadingSpinner';

export default function OwnerResidentsPage() {
  const [activeTab, setActiveTab] = useState('requests'); // 'requests' | 'residents'
  const [loading, setLoading] = useState(true);

  // Requests state
  const [requests, setRequests] = useState([]);
  const [pendingCount, setPendingCount] = useState(0);
  const [requestStatusFilter, setRequestStatusFilter] = useState('PENDING'); // 'PENDING' | 'APPROVED' | 'REJECTED' | 'ALL'
  const [selectedRequest, setSelectedRequest] = useState(null);
  const [reviewAction, setReviewAction] = useState(null); // 'approve' | 'reject'
  const [rejectReason, setRejectReason] = useState('');
  const [reviewSubmitting, setReviewSubmitting] = useState(false);

  // Residents state
  const [residents, setResidents] = useState([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState('ACTIVE');
  const [editingResident, setEditingResident] = useState(null);

  // Status Alerts
  const [alertData, setAlertData] = useState(null); // { type: 'success' | 'danger' | 'info', message: '' }

  const fetchRequests = async () => {
    try {
      const statusParam = requestStatusFilter === 'ALL' ? undefined : requestStatusFilter;
      const res = await API.get('/owner/occupancy-requests', {
        params: statusParam ? { status: statusParam } : {}
      });
      setRequests(res.data || []);

      if (requestStatusFilter === 'PENDING') {
        setPendingCount((res.data || []).length);
      } else {
        API.get('/owner/occupancy-requests', { params: { status: 'PENDING' } })
          .then(pRes => setPendingCount((pRes.data || []).length))
          .catch(() => {});
      }
    } catch (err) {
      console.error('Failed to fetch occupancy requests:', err);
    }
  };

  const fetchResidents = async () => {
    try {
      const res = await API.get('/owners/residents', {
        params: { query: searchQuery, status: statusFilter }
      });
      setResidents(res.data || []);
    } catch (err) {
      console.error('Failed to fetch residents:', err);
    }
  };

  const loadAllData = async () => {
    setLoading(true);
    await Promise.all([fetchRequests(), fetchResidents()]);
    setLoading(false);
  };

  useEffect(() => {
    loadAllData();
  }, []);

  useEffect(() => {
    fetchRequests();
  }, [requestStatusFilter]);

  useEffect(() => {
    fetchResidents();
  }, [searchQuery, statusFilter]);

  const handleReviewSubmit = async (e) => {
    e.preventDefault();
    if (!selectedRequest || !reviewAction) return;

    setReviewSubmitting(true);
    try {
      const isApproved = reviewAction === 'approve';
      const payload = {
        approved: isApproved,
        reason: isApproved ? null : (rejectReason || 'Request declined by building owner.')
      };

      await API.post(`/owner/occupancy-requests/${selectedRequest.requestId}/review`, payload);

      setAlertData({
        type: isApproved ? 'success' : 'info',
        message: isApproved
          ? `✓ Occupancy request approved! Resident ${selectedRequest.residentName} has been linked to ${selectedRequest.apartmentName || 'Room ' + selectedRequest.roomNumber} (${selectedRequest.buildingName}).`
          : `✓ Occupancy request declined for ${selectedRequest.residentName}. The unit remains vacant.`
      });

      setSelectedRequest(null);
      setReviewAction(null);
      setRejectReason('');
      await loadAllData();
    } catch (err) {
      alert(err.response?.data?.message || 'Failed to process request.');
    } finally {
      setReviewSubmitting(false);
    }
  };

  const handleUpdateResident = async (e) => {
    e.preventDefault();
    try {
      await API.put(`/owners/residents/${editingResident.residentId}`, {
        name: editingResident.name,
        phone: editingResident.phone,
        apartmentNumber: editingResident.apartmentNumber
      });
      setEditingResident(null);
      fetchResidents();
      setAlertData({ type: 'success', message: '✓ Resident details updated successfully.' });
    } catch (err) {
      alert(err.response?.data?.message || 'Update failed');
    }
  };

  const handleVacateResident = async (residentId, residentName, apartmentNumber) => {
    if (!window.confirm(`Are you sure you want to remove ${residentName} from Unit ${apartmentNumber}? This will vacate the unit while preserving historical records.`)) return;
    try {
      await API.post(`/owners/residents/${residentId}/vacate`);
      await fetchResidents();
      setAlertData({ type: 'success', message: `✓ ${residentName} removed from Unit ${apartmentNumber}. Unit is now vacant.` });
    } catch (err) {
      setAlertData({ type: 'danger', message: err.response?.data?.message || 'Failed to vacate resident.' });
    }
  };

  const handleDelete = async (id, name) => {
    if (!window.confirm(`Are you sure you want to delete resident record for ${name || 'this resident'}?`)) return;
    try {
      await API.delete(`/owners/residents/${id}`);
      await fetchResidents();
      setAlertData({ type: 'info', message: `✓ Resident record for ${name || 'resident'} removed successfully.` });
    } catch (err) {
      setAlertData({ type: 'danger', message: err.response?.data?.message || 'Delete failed.' });
    }
  };

  const activeResidents = residents.filter(r =>
    r.accountStatus === 'ACTIVE' &&
    r.apartmentNumber &&
    r.apartmentNumber.toUpperCase() !== 'PENDING' &&
    r.apartmentNumber.toUpperCase() !== 'REJECTED' &&
    !r.apartmentNumber.startsWith('FORMER-') &&
    r.apartmentNumber.toLowerCase() !== 'vacated'
  );

  if (loading) return <LoadingSpinner text="Loading Resident Onboarding & Management..." />;

  return (
    <div className="container-fluid py-4">
      {/* Header */}
      <div className="d-flex flex-wrap align-items-center justify-content-between gap-3 mb-4">
        <div>
          <h2 className="fw-bold mb-1 text-dark">Resident Onboarding & Management</h2>
          <p className="text-muted small mb-0">
            Review independent resident occupancy requests and manage active tenants across your properties
          </p>
        </div>

        {/* Tab Navigation Pill Buttons */}
        <div className="d-inline-flex p-1 bg-light border rounded-pill shadow-sm">
          <button
            className={`btn btn-sm rounded-pill px-3.5 py-1.5 fw-bold transition-all ${
              activeTab === 'requests'
                ? 'btn-dark text-white shadow-sm'
                : 'btn-light text-muted border-0'
            }`}
            onClick={() => setActiveTab('requests')}
          >
            <i className="bi bi-inbox-fill me-1.5"></i>
            Onboarding Requests
            {pendingCount > 0 && (
              <span className="badge bg-warning text-dark ms-2 rounded-pill px-2">
                {pendingCount} Pending
              </span>
            )}
          </button>
          <button
            className={`btn btn-sm rounded-pill px-3.5 py-1.5 fw-bold transition-all ${
              activeTab === 'residents'
                ? 'btn-dark text-white shadow-sm'
                : 'btn-light text-muted border-0'
            }`}
            onClick={() => setActiveTab('residents')}
          >
            <i className="bi bi-people-fill me-1.5"></i>
            Active Residents ({activeResidents.length})
          </button>
        </div>
      </div>

      {/* Global Toast Alert */}
      {alertData && (
        <div className={`alert alert-${alertData.type} alert-dismissible fade show rounded-4 shadow-sm mb-4`} role="alert">
          <div className="d-flex align-items-center gap-2">
            <i className={`bi ${alertData.type === 'success' ? 'bi-check-circle-fill text-success' : 'bi-info-circle-fill text-primary'} fs-5`}></i>
            <div className="fw-semibold text-dark">{alertData.message}</div>
          </div>
          <button type="button" className="btn-close" onClick={() => setAlertData(null)}></button>
        </div>
      )}

      {/* ========================================================================= */}
      {/* TAB 1: RESIDENT ONBOARDING REQUESTS */}
      {/* ========================================================================= */}
      {activeTab === 'requests' && (
        <div className="space-y-4">
          {/* Quick Filter Bar */}
          <div className="card glass-card border mb-3 p-3 shadow-sm bg-white rounded-4">
            <div className="d-flex align-items-center justify-content-between flex-wrap gap-2">
              <div className="d-flex align-items-center gap-2">
                <span className="fw-bold fs-7 text-dark">Filter Requests:</span>
                {['PENDING', 'APPROVED', 'REJECTED', 'ALL'].map(st => (
                  <button
                    key={st}
                    className={`btn btn-sm rounded-pill px-3 fw-semibold ${
                      requestStatusFilter === st
                        ? 'btn-dark'
                        : 'btn-outline-secondary bg-light'
                    }`}
                    onClick={() => setRequestStatusFilter(st)}
                  >
                    {st === 'PENDING' && <i className="bi bi-clock-history me-1 text-warning"></i>}
                    {st === 'APPROVED' && <i className="bi bi-check-circle me-1 text-success"></i>}
                    {st === 'REJECTED' && <i className="bi bi-x-circle me-1 text-danger"></i>}
                    {st}
                  </button>
                ))}
              </div>

              <div className="text-muted fs-8">
                Showing <strong>{requests.length}</strong> request(s)
              </div>
            </div>
          </div>

          {/* Requests Table */}
          <div className="card glass-card border shadow-sm bg-white rounded-4 overflow-hidden">
            <div className="card-body p-0">
              <div className="table-responsive">
                <table className="table custom-table mb-0 align-middle">
                  <thead className="bg-light">
                    <tr>
                      <th className="ps-4">Applicant Resident</th>
                      <th>Requested Unit</th>
                      <th>Building Property</th>
                      <th>Meter Assigned</th>
                      <th>Request Date</th>
                      <th>Status</th>
                      <th className="text-end pe-4">Review Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {requests.length ? (
                      requests.map(req => {
                        const isPending = req.status === 'PENDING';
                        const isApproved = req.status === 'APPROVED';
                        const isRejected = req.status === 'REJECTED';

                        return (
                          <tr key={req.requestId}>
                            <td className="ps-4">
                              <div className="d-flex align-items-center gap-2">
                                <div
                                  className="rounded-circle d-flex align-items-center justify-content-center text-white fw-bold fs-7"
                                  style={{ width: 36, height: 36, backgroundColor: '#005F73' }}
                                >
                                  {req.residentName ? req.residentName.charAt(0).toUpperCase() : 'R'}
                                </div>
                                <div>
                                  <div className="fw-bold text-dark">{req.residentName}</div>
                                  <div className="text-muted fs-8">
                                    <i className="bi bi-envelope me-1"></i>{req.residentEmail}
                                    {req.residentPhone && <span> • <i className="bi bi-telephone me-1"></i>{req.residentPhone}</span>}
                                  </div>
                                </div>
                              </div>
                            </td>

                            <td>
                              <div className="fw-bold text-dark">
                                Unit {req.roomNumber}
                              </div>
                              <div className="text-muted fs-8">
                                Floor {req.floorNumber} • <span className="badge bg-light text-dark border">{req.unitType || 'Flat'}</span>
                              </div>
                            </td>

                            <td>
                              <div className="fw-semibold text-dark">{req.buildingName}</div>
                              <div className="text-muted fs-8">{req.buildingLocation || req.buildingNumber}</div>
                            </td>

                            <td>
                              <code className="text-dark fw-bold">{req.meterNumber || 'Pending Link'}</code>
                            </td>

                            <td>
                              <div className="fs-8 text-dark fw-semibold">
                                {req.requestDate ? new Date(req.requestDate).toLocaleDateString() : 'N/A'}
                              </div>
                              <div className="fs-9 text-muted">
                                {req.requestDate ? new Date(req.requestDate).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : ''}
                              </div>
                            </td>

                            <td>
                              <span
                                className={`badge px-2.5 py-1.5 fs-8 fw-bold ${
                                  isPending
                                    ? 'bg-warning-subtle text-dark border border-warning'
                                    : isApproved
                                    ? 'bg-success-subtle text-success border border-success'
                                    : isRejected
                                    ? 'bg-danger-subtle text-danger border border-danger'
                                    : 'bg-light text-muted border'
                                }`}
                              >
                                {isPending && <i className="bi bi-hourglass-split me-1"></i>}
                                {isApproved && <i className="bi bi-check2-circle me-1"></i>}
                                {isRejected && <i className="bi bi-x-circle me-1"></i>}
                                {req.status}
                              </span>
                              {req.rejectionReason && (
                                <div className="text-danger fs-9 mt-1 text-truncate" style={{ maxWidth: 180 }} title={req.rejectionReason}>
                                  Reason: {req.rejectionReason}
                                </div>
                              )}
                            </td>

                            <td className="text-end pe-4">
                              {isPending ? (
                                <div className="d-inline-flex gap-1.5">
                                  <button
                                    className="btn btn-sm btn-success fw-bold px-2.5 shadow-sm"
                                    onClick={() => {
                                      setSelectedRequest(req);
                                      setReviewAction('approve');
                                    }}
                                    title="Approve occupancy request"
                                  >
                                    <i className="bi bi-check-lg me-1"></i> Approve
                                  </button>
                                  <button
                                    className="btn btn-sm btn-outline-danger fw-semibold px-2.5"
                                    onClick={() => {
                                      setSelectedRequest(req);
                                      setReviewAction('reject');
                                      setRejectReason('');
                                    }}
                                    title="Decline occupancy request"
                                  >
                                    <i className="bi bi-x-lg me-1"></i> Reject
                                  </button>
                                </div>
                              ) : (
                                <span className="text-muted fs-8">
                                  {req.reviewedBy ? `Reviewed by ${req.reviewedBy}` : 'Completed'}
                                </span>
                              )}
                            </td>
                          </tr>
                        );
                      })
                    ) : (
                      <tr>
                        <td colSpan="7" className="text-center py-5 text-muted">
                          <i className="bi bi-inbox text-secondary display-5 d-block mb-2"></i>
                          <h6 className="fw-bold text-dark">No Occupancy Requests Found</h6>
                          <p className="fs-8 text-muted mb-0">
                            {requestStatusFilter === 'PENDING'
                              ? 'All incoming resident occupancy requests have been reviewed!'
                              : 'No requests match the selected status filter.'}
                          </p>
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* TAB 2: ACTIVE RESIDENTS & OCCUPANTS */}
      {/* ========================================================================= */}
      {activeTab === 'residents' && (
        <div className="space-y-4">
          {/* Search & Status Filter */}
          <div className="card glass-card border mb-3 p-3 shadow-sm bg-white rounded-4">
            <div className="row g-3 align-items-center">
              <div className="col-md-6">
                <div className="input-group">
                  <span className="input-group-text bg-light border-end-0 text-muted"><i className="bi bi-search"></i></span>
                  <input
                    type="text"
                    className="form-control border-start-0 ps-0 bg-light"
                    placeholder="Search residents by name, room number, or email..."
                    value={searchQuery}
                    onChange={e => setSearchQuery(e.target.value)}
                  />
                </div>
              </div>
              <div className="col-md-6 d-flex align-items-center justify-content-md-end gap-2">
                <span className="badge bg-success-subtle text-success border border-success-subtle px-3 py-1.5 rounded-pill fw-semibold">
                  <i className="bi bi-check-circle-fill me-1"></i> Active Occupants Only ({activeResidents.length})
                </span>
              </div>
            </div>
          </div>

          {/* Active Residents Table */}
          <div className="card glass-card border shadow-sm bg-white rounded-4 overflow-hidden">
            <div className="card-body p-0">
              <div className="table-responsive">
                <table className="table custom-table mb-0 align-middle">
                  <thead className="bg-light">
                    <tr>
                      <th className="ps-4">Resident</th>
                      <th>Apartment Unit</th>
                      <th>Building Property</th>
                      <th>Meter Assigned</th>
                      <th>Account Status</th>
                      <th className="text-end pe-4">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {activeResidents.length ? (
                      activeResidents.map(r => (
                        <tr key={r.residentId}>
                          <td className="ps-4">
                            <div className="fw-bold text-dark">{r.name}</div>
                            <small className="text-muted">{r.email} • {r.phone || 'No phone'}</small>
                          </td>
                          <td>
                            <span className="badge bg-secondary-subtle text-dark border px-2.5 py-1">
                              Unit {r.apartmentNumber}
                            </span>
                          </td>
                          <td>{r.buildingName}</td>
                          <td><code className="text-dark fw-bold">{r.meterNumber || 'WM-Fixture'}</code></td>
                          <td>
                            <span className="badge bg-success-subtle text-success border border-success-subtle">
                              ACTIVE OCCUPANT
                            </span>
                          </td>
                          <td className="text-end pe-4">
                            <div className="d-inline-flex align-items-center gap-1.5">
                              <button
                                type="button"
                                className="btn btn-sm btn-outline-warning fw-semibold px-2 py-1 fs-8"
                                title="Remove resident from unit and vacate flat"
                                onClick={() => handleVacateResident(r.residentId, r.name, r.apartmentNumber)}
                              >
                                <i className="bi bi-box-arrow-right me-1"></i> Vacate Unit
                              </button>
                              <button
                                type="button"
                                className="btn btn-sm btn-outline-secondary px-2 py-1"
                                title="Edit resident details"
                                onClick={() => setEditingResident(r)}
                              >
                                <i className="bi bi-pencil"></i>
                              </button>
                              <button
                                type="button"
                                className="btn btn-sm btn-outline-danger px-2 py-1"
                                title="Delete resident record"
                                onClick={() => handleDelete(r.residentId, r.name)}
                              >
                                <i className="bi bi-trash"></i>
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan="6" className="text-center py-5 text-muted">
                          <i className="bi bi-people text-secondary display-5 d-block mb-2"></i>
                          <h6 className="fw-bold text-dark">No Active Residents Found</h6>
                          <p className="fs-8 text-muted mb-0">
                            {searchQuery
                              ? 'No active occupants match your search query.'
                              : 'No active occupants currently residing in your properties.'}
                          </p>
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL: REVIEW OCCUPANCY REQUEST (APPROVE / REJECT) */}
      {/* ========================================================================= */}
      {selectedRequest && reviewAction && (
        <div className="modal show d-block" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content border-0 shadow-lg rounded-4 overflow-hidden">
              <div className={`modal-header border-0 text-white ${reviewAction === 'approve' ? 'bg-success' : 'bg-danger'}`}>
                <div>
                  <h5 className="modal-title fw-bold mb-0">
                    <i className={`bi ${reviewAction === 'approve' ? 'bi-check-circle-fill' : 'bi-x-circle-fill'} me-2`}></i>
                    {reviewAction === 'approve' ? 'Approve Occupancy Request' : 'Decline Occupancy Request'}
                  </h5>
                  <small className="opacity-75">
                    {reviewAction === 'approve' ? 'Confirm resident move-in & meter activation' : 'Reject request and return unit to inventory'}
                  </small>
                </div>
                <button
                  type="button"
                  className="btn-close btn-close-white"
                  onClick={() => {
                    setSelectedRequest(null);
                    setReviewAction(null);
                  }}
                  disabled={reviewSubmitting}
                ></button>
              </div>

              <form onSubmit={handleReviewSubmit}>
                <div className="modal-body p-4">
                  {/* Summary Card */}
                  <div className="p-3 bg-light rounded-3 border mb-3">
                    <div className="row g-2 fs-7">
                      <div className="col-6">
                        <span className="text-muted d-block fs-8">Applicant Name</span>
                        <strong className="text-dark">{selectedRequest.residentName}</strong>
                      </div>
                      <div className="col-6">
                        <span className="text-muted d-block fs-8">Contact Email</span>
                        <strong className="text-dark text-truncate d-block">{selectedRequest.residentEmail}</strong>
                      </div>
                      <div className="col-6 mt-2 pt-2 border-top">
                        <span className="text-muted d-block fs-8">Property</span>
                        <strong className="text-dark">{selectedRequest.buildingName}</strong>
                      </div>
                      <div className="col-6 mt-2 pt-2 border-top">
                        <span className="text-muted d-block fs-8">Apartment Unit</span>
                        <strong className="text-dark">Unit {selectedRequest.roomNumber} (Floor {selectedRequest.floorNumber})</strong>
                      </div>
                      {selectedRequest.householdSize != null && (
                        <div className="col-12 mt-2 pt-2 border-top">
                          <span className="text-muted d-block fs-8">Declared Household Size</span>
                          <strong className="text-primary">
                            <i className="bi bi-people-fill me-1"></i>
                            {selectedRequest.householdSize} People
                            {selectedRequest.childrenCount ? ` (${selectedRequest.childrenCount} children)` : ''}
                          </strong>
                        </div>
                      )}
                    </div>
                  </div>

                  {selectedRequest.notes && (
                    <div className="alert alert-info py-2 px-3 fs-8 rounded-3 mb-3">
                      <i className="bi bi-chat-left-quote me-1.5"></i>
                      <strong>Resident's Note:</strong> "{selectedRequest.notes}"
                    </div>
                  )}

                  {reviewAction === 'approve' ? (
                    <div className="alert alert-success-subtle text-success-emphasis border border-success-subtle py-2.5 px-3 fs-8 rounded-3 mb-0">
                      <i className="bi bi-info-circle-fill me-1.5"></i>
                      <strong>Approval will execute the following:</strong>
                      <ul className="mb-0 ps-3 mt-1">
                        <li>Unit <strong>{selectedRequest.roomNumber}</strong> status changes to <strong>OCCUPIED</strong>.</li>
                        <li>Resident account is formally linked to this room and water meter fixture.</li>
                        <li>The resident receives an instant welcome notification in their dashboard.</li>
                      </ul>
                    </div>
                  ) : (
                    <div>
                      <label className="form-label fw-semibold fs-7 text-dark">
                        Reason for Declining (Optional)
                      </label>
                      <textarea
                        className="form-control fs-7 mb-2"
                        rows={3}
                        placeholder="e.g. Unit currently reserved under offline lease agreement, or applicant verification could not be completed."
                        value={rejectReason}
                        onChange={e => setRejectReason(e.target.value)}
                      ></textarea>
                      <small className="text-muted fs-8">
                        This explanation will be shared with the applicant. Unit {selectedRequest.roomNumber} will immediately revert to Vacant in your inventory.
                      </small>
                    </div>
                  )}
                </div>

                <div className="modal-footer border-0 bg-light">
                  <button
                    type="button"
                    className="btn btn-outline-secondary"
                    onClick={() => {
                      setSelectedRequest(null);
                      setReviewAction(null);
                    }}
                    disabled={reviewSubmitting}
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    className={`btn fw-bold px-3 ${reviewAction === 'approve' ? 'btn-success' : 'btn-danger'}`}
                    disabled={reviewSubmitting}
                  >
                    {reviewSubmitting ? (
                      <span><span className="spinner-border spinner-border-sm me-1.5"></span>Processing...</span>
                    ) : reviewAction === 'approve' ? (
                      <span><i className="bi bi-check2-circle me-1.5"></i> Confirm Approval</span>
                    ) : (
                      <span><i className="bi bi-x-circle me-1.5"></i> Confirm Rejection</span>
                    )}
                  </button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL: EDIT RESIDENT */}
      {/* ========================================================================= */}
      {editingResident && (
        <div className="modal show d-block" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          <div className="modal-dialog modal-dialog-centered">
            <div className="modal-content border-0 shadow-lg rounded-4">
              <div className="modal-header border-0 pb-0">
                <h5 className="modal-title fw-bold text-dark">Edit Resident Details</h5>
                <button type="button" className="btn-close" onClick={() => setEditingResident(null)}></button>
              </div>
              <form onSubmit={handleUpdateResident}>
                <div className="modal-body">
                  <div className="mb-3">
                    <label className="form-label fw-semibold fs-7 text-dark">Resident Name *</label>
                    <input
                      type="text"
                      className="form-control"
                      value={editingResident.name}
                      onChange={e => setEditingResident({ ...editingResident, name: e.target.value })}
                      required
                    />
                  </div>
                  <div className="mb-3">
                    <label className="form-label fw-semibold fs-7 text-dark">Phone Number</label>
                    <input
                      type="text"
                      className="form-control"
                      value={editingResident.phone || ''}
                      onChange={e => setEditingResident({ ...editingResident, phone: e.target.value })}
                    />
                  </div>
                  <div className="mb-3">
                    <label className="form-label fw-semibold fs-7 text-dark">Apartment Number *</label>
                    <input
                      type="text"
                      className="form-control"
                      value={editingResident.apartmentNumber}
                      onChange={e => setEditingResident({ ...editingResident, apartmentNumber: e.target.value })}
                      required
                    />
                  </div>
                </div>
                <div className="modal-footer border-0">
                  <button type="button" className="btn btn-light" onClick={() => setEditingResident(null)}>Cancel</button>
                  <button type="submit" className="btn btn-dark px-4 fw-bold">Save Changes</button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
