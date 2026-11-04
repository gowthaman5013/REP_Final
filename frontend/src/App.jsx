import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './shared/context/AuthContext';

import PublicLayout from './shared/layouts/PublicLayout';
import DashboardLayout from './shared/layouts/DashboardLayout';

import LandingPage from './shared/auth/LandingPage';
import LoginPage from './shared/auth/LoginPage';
import RegisterOwnerPage from './shared/auth/RegisterOwnerPage';
import RegisterResidentPage from './shared/auth/RegisterResidentPage';
import AcceptInvitationPage from './shared/auth/AcceptInvitationPage';

import AdminDashboard from './superadmin/pages/AdminDashboard';
import AdminOwnersPage from './superadmin/pages/AdminOwnersPage';
import AdminPendingOwnersPage from './superadmin/pages/AdminPendingOwnersPage';
import AdminHistoryPage from './superadmin/pages/AdminHistoryPage';

import OwnerDashboard from './owner/pages/OwnerDashboard';
import OwnerBuildingsPage from './owner/pages/OwnerBuildingsPage';
import OwnerResidentsPage from './owner/pages/OwnerResidentsPage';
import OwnerUsagePage from './owner/pages/OwnerUsagePage';
import OwnerUsageComparisonPage from './owner/pages/OwnerUsageComparisonPage';
import OwnerBillingPage from './owner/pages/OwnerBillingPage';
import BulkBillingPage from './owner/pages/BulkBillingPage';

import ResidentDashboard from './resident/pages/ResidentDashboard';
import ResidentBillsPage from './resident/pages/ResidentBillsPage';
import ResidentComparisonPage from './resident/pages/ResidentComparisonPage';
import ResidentNotificationsPage from './resident/pages/ResidentNotificationsPage';

import ProfilePage from './shared/pages/ProfilePage';
import InvoicesPage from './owner/pages/InvoicesPage';
import OwnerHistoryPage from './owner/pages/OwnerHistoryPage';

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          {/* Public Routes */}
          <Route element={<PublicLayout />}>
            <Route path="/" element={<LoginPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register-owner" element={<RegisterOwnerPage />} />
            <Route path="/register-resident" element={<RegisterResidentPage />} />
            <Route path="/register" element={<RegisterResidentPage />} />
            <Route path="/accept-invitation" element={<AcceptInvitationPage />} />
          </Route>

          {/* Super Admin Routes */}
          <Route element={<DashboardLayout allowedRole="SUPER_ADMIN" />}>
            <Route path="/admin/dashboard" element={<AdminDashboard />} />
            <Route path="/admin/owners" element={<AdminOwnersPage />} />
            <Route path="/admin/pending-owners" element={<AdminPendingOwnersPage />} />
            <Route path="/admin/history" element={<AdminHistoryPage />} />
            <Route path="/admin/profile" element={<ProfilePage />} />
          </Route>

          {/* Building Owner Routes */}
          <Route element={<DashboardLayout allowedRole="BUILDING_OWNER" />}>
            <Route path="/owner/dashboard" element={<OwnerDashboard />} />
            <Route path="/owner/buildings" element={<OwnerBuildingsPage />} />
            <Route path="/owner/residents" element={<OwnerResidentsPage />} />
            <Route path="/owner/usage" element={<OwnerUsagePage />} />
            <Route path="/owner/usage-comparison" element={<OwnerUsageComparisonPage />} />
            <Route path="/owner/billing" element={<OwnerBillingPage />} />
            <Route path="/owner/bulk-billing" element={<BulkBillingPage />} />
            <Route path="/owner/invoices" element={<InvoicesPage />} />
            <Route path="/owner/history" element={<OwnerHistoryPage />} />
            <Route path="/owner/profile" element={<ProfilePage />} />
          </Route>

          {/* Resident Routes */}
          <Route element={<DashboardLayout allowedRole="RESIDENT" />}>
            <Route path="/resident/dashboard" element={<ResidentDashboard />} />
            <Route path="/resident/bills" element={<ResidentBillsPage />} />
            <Route path="/resident/comparison" element={<ResidentComparisonPage />} />
            <Route path="/resident/notifications" element={<ResidentNotificationsPage />} />
            <Route path="/resident/profile" element={<ProfilePage />} />
          </Route>

          {/* Fallback Catch-All */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}
