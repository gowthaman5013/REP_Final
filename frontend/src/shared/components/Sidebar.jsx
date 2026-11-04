import React, { useContext } from 'react';
import { NavLink } from 'react-router-dom';
import { AuthContext } from '../context/AuthContext';

export default function Sidebar() {
  const { user } = useContext(AuthContext);

  if (!user) return null;

  const role = user.role;

  return (
    <aside className="app-sidebar d-none d-md-block">
      <div className="text-uppercase text-light opacity-50 fw-bold px-3 mb-3 fs-7">
        Navigation Menu
      </div>
      <nav className="nav flex-column">
        {role === 'SUPER_ADMIN' && (
          <>
            <NavLink to="/admin/dashboard" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-speedometer2"></i> Dashboard
            </NavLink>
            <NavLink to="/admin/owners" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-people-fill"></i> All Building Owners
            </NavLink>
            <NavLink to="/admin/pending-owners" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-person-check-fill"></i> Pending Approvals
            </NavLink>
            <NavLink to="/admin/history" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-clock-history"></i> Owner History
            </NavLink>
          </>
        )}

        {role === 'BUILDING_OWNER' && (
          <>
            <NavLink to="/owner/dashboard" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-speedometer2"></i> Owner Dashboard
            </NavLink>
            <NavLink to="/owner/buildings" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-building"></i> Buildings
            </NavLink>
            <NavLink to="/owner/residents" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-people"></i> Residents Management
            </NavLink>
            <NavLink to="/owner/usage" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-water"></i> Water Usage Logs
            </NavLink>
            <NavLink to="/owner/usage-comparison" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-bar-chart-line"></i> Usage Comparison
            </NavLink>
            <NavLink to="/owner/billing" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-receipt"></i> Monthly Bills
            </NavLink>
            <NavLink to="/owner/bulk-billing" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-file-earmark-spreadsheet"></i> Bulk Billing Import
            </NavLink>
            <NavLink to="/owner/invoices" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-receipt-cutoff"></i> Invoices
            </NavLink>

            <NavLink to="/owner/history" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-clock-history"></i> Resident History
            </NavLink>
          </>
        )}

        {role === 'RESIDENT' && (
          <>
            <NavLink to="/resident/dashboard" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-speedometer2"></i> Resident Dashboard
            </NavLink>

            <NavLink to="/resident/bills" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-card-checklist"></i> My Bills & Payments
            </NavLink>
            <NavLink to="/resident/comparison" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-bar-chart-steps"></i> Usage Comparison
            </NavLink>
            <NavLink to="/resident/notifications" className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}>
              <i className="bi bi-bell"></i> Notifications
            </NavLink>
          </>
        )}
      </nav>
    </aside>
  );
}
