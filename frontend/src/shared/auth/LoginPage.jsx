import React, { useState, useContext, useEffect } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { AuthContext } from '../context/AuthContext';

export default function LoginPage() {
  const [activeRole, setActiveRole] = useState('SUPER_ADMIN'); // 'SUPER_ADMIN', 'BUILDING_OWNER', 'RESIDENT'
  const [email, setEmail] = useState('admin@smartwater.com');
  const [password, setPassword] = useState('Admin@123456');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const { user, login } = useContext(AuthContext);
  const navigate = useNavigate();

  useEffect(() => {
    if (user) {
      if (user.role === 'SUPER_ADMIN') {
        navigate('/admin/dashboard', { replace: true });
      } else if (user.role === 'BUILDING_OWNER') {
        navigate('/owner/dashboard', { replace: true });
      } else if (user.role === 'RESIDENT') {
        navigate('/resident/dashboard', { replace: true });
      }
    }
  }, [user, navigate]);

  const handleRoleSelect = (role) => {
    setActiveRole(role);
    setError('');
    if (role === 'SUPER_ADMIN') {
      setEmail('admin@smartwater.com');
      setPassword('Admin@123456');
    } else {
      setEmail('');
      setPassword('');
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);

    try {
      const user = await login(email, password, activeRole);
      if (user.role === 'SUPER_ADMIN') {
        navigate('/admin/dashboard');
      } else if (user.role === 'BUILDING_OWNER') {
        navigate('/owner/dashboard');
      } else if (user.role === 'RESIDENT') {
        navigate('/resident/dashboard');
      }
    } catch (err) {
      setError(err.response?.data?.message || 'Invalid credentials or role mismatch.');
    } finally {
      setLoading(false);
    }
  };

  const getRoleHeader = () => {
    switch (activeRole) {
      case 'SUPER_ADMIN':
        return {
          title: 'Super Admin Portal',
          subtitle: 'System administrator login. Manages owner approvals and global settings.',
          icon: 'bi-shield-lock'
        };
      case 'BUILDING_OWNER':
        return {
          title: 'Building Owner Portal',
          subtitle: 'Property manager login. Manage buildings, invite residents, and generate bills.',
          icon: 'bi-building'
        };
      case 'RESIDENT':
        return {
          title: 'Resident Portal',
          subtitle: 'Tenant portal login. Track water usage, view invoices, and pay bills.',
          icon: 'bi-person-badge'
        };
      default:
        return { title: 'Portal Login', subtitle: '', icon: 'bi-person' };
    }
  };

  const headerInfo = getRoleHeader();

  return (
    <div className="min-vh-100 d-flex align-items-center justify-content-center py-5" style={{ backgroundColor: '#F0FAFF' }}>
      <div className="container" style={{ maxWidth: 620 }}>
        
        {/* 3 Role Selection Cards */}
        <div className="text-center mb-4">
          <h4 className="fw-bold mb-2" style={{ color: '#003554' }}>Select Portal Role</h4>
          <p className="text-muted fs-7 mb-3">Choose your account category to proceed to authentication</p>
          <div className="row g-3">
            
            {/* Super Admin Box */}
            <div className="col-4">
              <div
                onClick={() => handleRoleSelect('SUPER_ADMIN')}
                className="card text-center p-3 cursor-pointer transition-all border rounded-4"
                style={{
                  cursor: 'pointer',
                  backgroundColor: activeRole === 'SUPER_ADMIN' ? '#005F73' : '#ffffff',
                  color: activeRole === 'SUPER_ADMIN' ? '#ffffff' : '#003554',
                  borderColor: activeRole === 'SUPER_ADMIN' ? '#005F73' : '#D4EBF8',
                  boxShadow: activeRole === 'SUPER_ADMIN' ? '0 6px 16px rgba(0, 95, 115, 0.25)' : '0 2px 8px rgba(0, 95, 115, 0.04)'
                }}
              >
                <i className={`bi bi-shield-lock fs-3 d-block mb-1 ${activeRole === 'SUPER_ADMIN' ? 'text-white' : ''}`} style={{ color: activeRole === 'SUPER_ADMIN' ? '#ffffff' : '#005F73' }}></i>
                <span className="fw-bold fs-7 d-block">Super Admin</span>
                <small className={`fs-8 d-none d-sm-block ${activeRole === 'SUPER_ADMIN' ? 'text-white opacity-85' : 'text-muted'}`}>System Admin</small>
              </div>
            </div>

            {/* Building Owner Box */}
            <div className="col-4">
              <div
                onClick={() => handleRoleSelect('BUILDING_OWNER')}
                className="card text-center p-3 cursor-pointer transition-all border rounded-4"
                style={{
                  cursor: 'pointer',
                  backgroundColor: activeRole === 'BUILDING_OWNER' ? '#005F73' : '#ffffff',
                  color: activeRole === 'BUILDING_OWNER' ? '#ffffff' : '#003554',
                  borderColor: activeRole === 'BUILDING_OWNER' ? '#005F73' : '#D4EBF8',
                  boxShadow: activeRole === 'BUILDING_OWNER' ? '0 6px 16px rgba(0, 95, 115, 0.25)' : '0 2px 8px rgba(0, 95, 115, 0.04)'
                }}
              >
                <i className={`bi bi-building fs-3 d-block mb-1 ${activeRole === 'BUILDING_OWNER' ? 'text-white' : ''}`} style={{ color: activeRole === 'BUILDING_OWNER' ? '#ffffff' : '#005F73' }}></i>
                <span className="fw-bold fs-7 d-block">Building Owner</span>
                <small className={`fs-8 d-none d-sm-block ${activeRole === 'BUILDING_OWNER' ? 'text-white opacity-85' : 'text-muted'}`}>Property Manager</small>
              </div>
            </div>

            {/* Resident Box */}
            <div className="col-4">
              <div
                onClick={() => handleRoleSelect('RESIDENT')}
                className="card text-center p-3 cursor-pointer transition-all border rounded-4"
                style={{
                  cursor: 'pointer',
                  backgroundColor: activeRole === 'RESIDENT' ? '#005F73' : '#ffffff',
                  color: activeRole === 'RESIDENT' ? '#ffffff' : '#003554',
                  borderColor: activeRole === 'RESIDENT' ? '#005F73' : '#D4EBF8',
                  boxShadow: activeRole === 'RESIDENT' ? '0 6px 16px rgba(0, 95, 115, 0.25)' : '0 2px 8px rgba(0, 95, 115, 0.04)'
                }}
              >
                <i className={`bi bi-person-badge fs-3 d-block mb-1 ${activeRole === 'RESIDENT' ? 'text-white' : ''}`} style={{ color: activeRole === 'RESIDENT' ? '#ffffff' : '#005F73' }}></i>
                <span className="fw-bold fs-7 d-block">Resident</span>
                <small className={`fs-8 d-none d-sm-block ${activeRole === 'RESIDENT' ? 'text-white opacity-85' : 'text-muted'}`}>Tenant Account</small>
              </div>
            </div>

          </div>
        </div>

        {/* Login Form Box */}
        <div className="card glass-card border p-4 p-md-5 shadow-sm bg-white rounded-4" style={{ borderColor: '#D4EBF8' }}>
          <div className="text-center mb-4">
            <div className="d-inline-flex align-items-center gap-2 mb-2">
              <i className={`bi ${headerInfo.icon} fs-4`} style={{ color: '#005F73' }}></i>
              <span className="badge px-3 py-1 fs-7 fw-bold" style={{ backgroundColor: '#E3F6FF', color: '#005F73', border: '1px solid #D4EBF8' }}>
                {activeRole.replace('_', ' ')}
              </span>
            </div>
            <h3 className="fw-bold mb-1" style={{ color: '#003554' }}>{headerInfo.title}</h3>
            <p className="text-muted fs-7 mb-0">{headerInfo.subtitle}</p>
          </div>

          {error && (
            <div className="alert alert-danger py-2 px-3 small rounded-3 mb-3 border-danger-subtle">
              <i className="bi bi-exclamation-triangle-fill me-2"></i>
              {error}
            </div>
          )}

          <form onSubmit={handleSubmit}>
            <div className="mb-3">
              <label className="form-label fw-semibold fs-7" style={{ color: '#003554' }}>Email Address</label>
              <div className="input-group">
                <span className="input-group-text bg-white border-end-0 text-muted" style={{ borderColor: '#D4EBF8' }}><i className="bi bi-envelope"></i></span>
                <input
                  type="email"
                  className="form-control border-start-0 ps-0 bg-white"
                  style={{ borderColor: '#D4EBF8' }}
                  placeholder="name@example.com"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                />
              </div>
            </div>

            <div className="mb-4">
              <label className="form-label fw-semibold fs-7" style={{ color: '#003554' }}>Password</label>
              <div className="input-group">
                <span className="input-group-text bg-white border-end-0 text-muted" style={{ borderColor: '#D4EBF8' }}><i className="bi bi-lock"></i></span>
                <input
                  type="password"
                  className="form-control border-start-0 ps-0 bg-white"
                  style={{ borderColor: '#D4EBF8' }}
                  placeholder="••••••••"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
              </div>
            </div>

            <button type="submit" className="btn btn-sprink-primary w-100 py-2.5 fw-bold" disabled={loading}>
              {loading ? (
                <span><span className="spinner-border spinner-border-sm me-2"></span>Authenticating...</span>
              ) : (
                <span>Sign In as {activeRole.replace('_', ' ')} <i className="bi bi-arrow-right ms-2"></i></span>
              )}
            </button>
          </form>

          {/* Role-Specific Action Footer */}
          <div className="mt-4 border-top pt-3 text-center" style={{ borderColor: '#E8F4FA' }}>
            {activeRole === 'BUILDING_OWNER' && (
              <div>
                <span className="text-muted fs-7">New Building Owner? </span>
                <Link to="/register-owner" className="text-decoration-none fw-bold fs-7" style={{ color: '#005F73' }}>
                  Register Your Property <i className="bi bi-box-arrow-up-right ms-1"></i>
                </Link>
              </div>
            )}

            {activeRole === 'RESIDENT' && (
              <div>
                <span className="text-muted fs-7">New Resident? </span>
                <Link to="/register-resident" className="text-decoration-none fw-bold fs-7" style={{ color: '#005F73' }}>
                  Create Resident Account <i className="bi bi-person-plus ms-1"></i>
                </Link>
              </div>
            )}

            {activeRole === 'SUPER_ADMIN' && (
              <small className="text-muted fs-8">
                <i className="bi bi-shield-check me-1" style={{ color: '#005F73' }}></i> Super Admin accounts are pre-seeded in the database.
              </small>
            )}
          </div>
        </div>

      </div>
    </div>
  );
}
