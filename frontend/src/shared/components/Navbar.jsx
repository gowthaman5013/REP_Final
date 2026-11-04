import React, { useContext } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AuthContext } from '../context/AuthContext';

export default function Navbar() {
  const { user, logout } = useContext(AuthContext);
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const getRoleBadge = (role) => {
    switch (role) {
      case 'SUPER_ADMIN':
        return (
          <span className="badge px-2.5 py-1 rounded-pill fw-semibold fs-8" style={{ backgroundColor: '#1E293B', color: '#ffffff' }}>
            <i className="bi bi-shield-lock-fill me-1"></i> Super Admin
          </span>
        );
      case 'BUILDING_OWNER':
        return (
          <span className="badge px-2.5 py-1 rounded-pill fw-semibold fs-8" style={{ backgroundColor: '#005F73', color: '#ffffff' }}>
            <i className="bi bi-building me-1"></i> Building Owner
          </span>
        );
      case 'RESIDENT':
        return (
          <span className="badge px-2.5 py-1 rounded-pill fw-semibold fs-8" style={{ backgroundColor: '#0A9396', color: '#ffffff' }}>
            <i className="bi bi-person-badge me-1"></i> Resident
          </span>
        );
      default:
        return null;
    }
  };

  const getProfilePath = (role) => {
    if (!role) return '/login';
    if (role === 'SUPER_ADMIN') return '/admin/profile';
    if (role === 'BUILDING_OWNER') return '/owner/profile';
    return '/resident/profile';
  };

  return (
    <nav className="navbar navbar-expand-lg app-navbar sticky-top">
      <div className="container-fluid">
        <Link className="navbar-brand d-flex align-items-center gap-2 brand-font fs-4" to={user ? `/${user.role.toLowerCase().replace('_', '') === 'superadmin' ? 'admin' : user.role.toLowerCase()}/dashboard` : '/'}>
          <div className="rounded-circle p-1.5 d-flex align-items-center justify-content-center" style={{ width: 36, height: 36, backgroundColor: '#005F73' }}>
            <i className="bi bi-droplet-fill fs-5 text-white"></i>
          </div>
          <span className="fw-extrabold" style={{ color: '#005F73' }}>sprink<span className="fw-semibold text-muted" style={{ fontSize: '0.85rem' }}> | AquaFlow</span></span>
        </Link>

        <button className="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#navbarContent">
          <span className="navbar-toggler-icon"></span>
        </button>

        <div className="collapse navbar-collapse" id="navbarContent">
          <ul className="navbar-nav me-auto mb-2 mb-lg-0 ms-lg-4">
          </ul>

          <div className="d-flex align-items-center gap-3">
            {user ? (
              <div className="dropdown">
                <button className="btn user-profile-btn dropdown-toggle d-flex align-items-center gap-2" type="button" data-bs-toggle="dropdown">
                  <div className="text-white rounded-circle fw-bold d-flex align-items-center justify-content-center" style={{ width: 34, height: 34, fontSize: '0.9rem', backgroundColor: '#005F73' }}>
                    {user.name.charAt(0).toUpperCase()}
                  </div>
                  <div className="text-start d-none d-md-block">
                    <div className="fw-bold leading-tight fs-7" style={{ color: '#003554' }}>{user.name}</div>
                    <small className="text-muted fs-8">{user.role}</small>
                  </div>
                </button>
                <ul className="dropdown-menu dropdown-menu-end shadow border-0 mt-2 rounded-4 p-2" style={{ width: 230 }}>
                  <li className="px-3 py-2 border-bottom">
                    <div className="fw-bold fs-7 mb-0.5" style={{ color: '#003554' }}>{user.name}</div>
                    <small className="text-muted fs-8 d-block mb-2">{user.email}</small>
                    <div>{getRoleBadge(user.role)}</div>
                  </li>
                  <li className="pt-2">
                    <Link className="dropdown-item px-3 py-2 fs-7 d-flex align-items-center gap-2 text-dark fw-semibold rounded-2" to={getProfilePath(user.role)}>
                      <i className="bi bi-person-circle text-primary fs-6"></i>
                      <span>My Profile</span>
                    </Link>
                  </li>
                  <li className="pt-1">
                    <button className="btn btn-link text-decoration-none text-start w-100 px-3 py-2 fs-7 d-flex align-items-center gap-2 text-dark fw-semibold rounded-2" onClick={handleLogout}>
                      <i className="bi bi-box-arrow-right text-danger fs-6"></i>
                      <span>Logout</span>
                    </button>
                  </li>
                </ul>
              </div>
            ) : null}
          </div>
        </div>
      </div>
    </nav>
  );
}
