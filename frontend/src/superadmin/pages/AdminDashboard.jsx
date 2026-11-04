import React, { useState, useEffect } from 'react';
import API from '../../shared/services/api';
import StatCard from '../../shared/components/StatCard';
import LoadingSpinner from '../../shared/components/LoadingSpinner';

export default function AdminDashboard() {
  const [stats, setStats] = useState(null);
  const [loading, setLoading] = useState(true);
  const [deletionNotice, setDeletionNotice] = useState(null);

  const fetchStats = async () => {
    try {
      const res = await API.get('/admin/dashboard');
      setStats(res.data);
    } catch (err) {
      console.error('Failed to load admin stats', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchStats();
  }, []);

  const handleApprove = async (id) => {
    try {
      await API.put(`/admin/owners/${id}/approve`);
      fetchStats();
    } catch (err) {
      alert(err.response?.data?.message || 'Approval failed');
    }
  };

  const handleReject = async (id) => {
    if (window.confirm('Are you sure you want to reject this building owner?')) {
      try {
        await API.put(`/admin/owners/${id}/reject`);
        fetchStats();
      } catch (err) {
        alert(err.response?.data?.message || 'Rejection failed');
      }
    }
  };

  const handleDeleteOwner = async (ownerId, ownerName) => {
    if (window.confirm(`Are you sure you want to remove building owner "${ownerName}" and all their associated properties from the platform? This action cannot be undone.`)) {
      try {
        setDeletionNotice(null);
        await API.delete(`/admin/owners/${ownerId}`);
        fetchStats();
      } catch (err) {
        const errorMsg = err.response?.data?.message || 'Failed to delete building owner';
        setDeletionNotice(errorMsg);
        alert(errorMsg);
      }
    }
  };

  if (loading) return <LoadingSpinner text="Fetching Super Admin Analytics..." />;

  return (
    <div className="container-fluid py-4">
      <div className="d-flex align-items-center justify-content-between mb-4 flex-wrap gap-3">
        <div>
          <h2 className="fw-bold mb-1 text-dark">Super Admin Control Center</h2>
          <p className="text-muted small mb-0">System-wide governance, owner approvals, and revenue overview</p>
        </div>
      </div>

      <div className="row g-3 mb-4">
        <div className="col-md-4">
          <StatCard title="Total Building Owners" value={stats?.totalOwners || 0} icon="bi-person-badge" />
        </div>
        <div className="col-md-4">
          <StatCard title="Pending Approvals" value={stats?.pendingOwners || 0} icon="bi-clock-history" subtext="Requires Action" />
        </div>
        <div className="col-md-4">
          <StatCard title="Total Buildings" value={stats?.totalBuildings || 0} icon="bi-building" />
        </div>
      </div>

      {deletionNotice && (
        <div className="alert alert-warning border-warning shadow-sm alert-dismissible fade show mb-4" role="alert">
          <div className="d-flex align-items-start gap-3">
            <i className="bi bi-exclamation-triangle-fill fs-3 text-warning flex-shrink-0 mt-1"></i>
            <div>
              <h5 className="alert-heading fw-bold mb-1">Owner Deletion Safeguard: Outstanding Resident Balance</h5>
              <p className="mb-2 text-dark">{deletionNotice}</p>
              <div className="small text-muted">
                <i className="bi bi-info-circle me-1"></i>
                Notifications and emails have been automatically dispatched to the building owner and all residents with outstanding balances requesting them to finish all billing and settle accounts before deletion.
              </div>
            </div>
            <button type="button" className="btn-close ms-auto" aria-label="Close" onClick={() => setDeletionNotice(null)}></button>
          </div>
        </div>
      )}

      {/* Building Owners Directory */}
      <div className="card glass-card border shadow-sm bg-white">
        <div className="card-header bg-transparent border-0 pt-4 px-4 d-flex justify-content-between align-items-center">
          <h5 className="fw-bold mb-0 text-dark">Building Owners Directory</h5>
        </div>
        <div className="card-body p-0">
          <div className="table-responsive">
            <table className="table custom-table mb-0 align-middle">
              <thead>
                <tr>
                  <th>Owner Name</th>
                  <th>Email</th>
                  <th>Phone</th>
                  <th>Buildings</th>
                  <th>Status</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {stats?.recentOwners?.length ? (
                  stats.recentOwners.map((owner) => (
                    <tr key={owner.ownerId}>
                      <td className="fw-bold text-dark">{owner.name}</td>
                      <td className="text-muted">{owner.email}</td>
                      <td className="text-muted">{owner.phone}</td>
                      <td>
                        <span className="badge bg-secondary-subtle text-dark border px-2.5 py-1">
                          {owner.totalBuildings} Buildings
                        </span>
                      </td>
                      <td>
                        {owner.approvalStatus === 'APPROVED' && (
                          <span className="badge bg-success-subtle text-success border border-success-subtle px-3 py-1.5 rounded-pill fw-semibold fs-7">
                            <i className="bi bi-check-circle-fill me-1"></i> APPROVED
                          </span>
                        )}
                        {owner.approvalStatus === 'PENDING' && (
                          <span className="badge bg-warning-subtle text-dark border border-warning-subtle px-3 py-1.5 rounded-pill fw-semibold fs-7">
                            <i className="bi bi-clock-fill text-warning me-1"></i> PENDING
                          </span>
                        )}
                        {owner.approvalStatus === 'REJECTED' && (
                          <span className="badge bg-danger-subtle text-danger border border-danger-subtle px-3 py-1.5 rounded-pill fw-semibold fs-7">
                            <i className="bi bi-x-circle-fill me-1"></i> REJECTED
                          </span>
                        )}
                      </td>
                      <td>
                        <div className="d-flex align-items-center gap-2">
                          {owner.approvalStatus === 'PENDING' && (
                            <>
                              <button
                                className="btn btn-sm btn-dark px-2.5 py-1 fw-semibold rounded-2 shadow-sm"
                                onClick={() => handleApprove(owner.ownerId)}
                              >
                                <i className="bi bi-check2 me-1"></i> Approve
                              </button>
                              <button
                                className="btn btn-sm btn-outline-warning px-2.5 py-1 fw-semibold rounded-2"
                                onClick={() => handleReject(owner.ownerId)}
                              >
                                <i className="bi bi-x-lg me-1"></i> Reject
                              </button>
                            </>
                          )}
                          <button
                            className="btn btn-sm btn-outline-danger px-2.5 py-1 fw-semibold rounded-2 d-flex align-items-center gap-1"
                            title="Delete and remove this building owner"
                            onClick={() => handleDeleteOwner(owner.ownerId, owner.name)}
                          >
                            <i className="bi bi-trash"></i>
                            <span>Delete</span>
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))
                ) : (
                  <tr>
                    <td colSpan="6" className="text-center py-4 text-muted">No owners registered yet.</td>
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
