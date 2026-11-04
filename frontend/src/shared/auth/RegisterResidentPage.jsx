import React, { useState, useEffect, useContext } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { AuthContext } from '../context/AuthContext';
import API from '../services/api';

export default function RegisterResidentPage() {
  const navigate = useNavigate();
  const { registerResident } = useContext(AuthContext);

  const [formData, setFormData] = useState({
    name: '',
    email: '',
    phone: '',
    password: '',
    confirmPassword: '',
    buildingId: '',
    apartmentId: '',
    householdSize: '',
    childrenCount: ''
  });

  const [availableApartments, setAvailableApartments] = useState([]);
  const [buildingsList, setBuildingsList] = useState([]);
  const [fieldErrors, setFieldErrors] = useState({});
  const [error, setError] = useState('');
  const [successMsg, setSuccessMsg] = useState('');
  const [loading, setLoading] = useState(false);
  const [loadingApts, setLoadingApts] = useState(false);

  // Load available buildings on mount
  useEffect(() => {
    setLoadingApts(true);
    API.get('/apartments/buildings')
      .then((res) => {
        const list = res?.data || [];
        setBuildingsList(list);
      })
      .catch((err) => {
        console.warn('Could not load properties/buildings for self-registration:', err);
      })
      .finally(() => {
        setLoadingApts(false);
      });
  }, []);

  // Fetch available units dynamically when building changes
  const fetchApartmentsForBuilding = async (bId) => {
    if (!bId) {
      setAvailableApartments([]);
      return;
    }
    try {
      setLoadingApts(true);
      const res = await API.get('/apartments/available', { params: { buildingId: bId } });
      const newApts = res.data || [];
      setAvailableApartments(newApts);
    } catch (err) {
      console.warn('Failed to load apartments for building', bId, err);
      setAvailableApartments([]);
    } finally {
      setLoadingApts(false);
    }
  };

  const handleBuildingChange = (e) => {
    const bId = e.target.value;
    setFormData(prev => ({
      ...prev,
      buildingId: bId,
      apartmentId: ''
    }));

    setFieldErrors(prev => {
      const next = { ...prev };
      delete next.buildingId;
      delete next.apartmentId;
      return next;
    });

    if (bId) {
      fetchApartmentsForBuilding(bId);
    } else {
      setAvailableApartments([]);
    }
  };

  const handleChange = (e) => {
    const { name, value } = e.target;
    setFormData(prev => ({ ...prev, [name]: value }));
    setFieldErrors(prev => {
      const next = { ...prev };
      delete next[name];
      return next;
    });
  };

  const validate = () => {
    const errors = {};

    if (!formData.name.trim()) {
      errors.name = 'Full name is required.';
    }

    if (!formData.email.trim()) {
      errors.email = 'Email address is required.';
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.email.trim())) {
      errors.email = 'Please enter a valid email address.';
    }

    if (!formData.phone.trim()) {
      errors.phone = 'Phone number is required.';
    }

    if (!formData.password) {
      errors.password = 'Password is required.';
    } else if (formData.password.length < 6) {
      errors.password = 'Password must be at least 6 characters.';
    }

    if (!formData.confirmPassword) {
      errors.confirmPassword = 'Confirm password is required.';
    } else if (formData.password !== formData.confirmPassword) {
      errors.confirmPassword = 'Passwords do not match.';
    }

    if (!formData.buildingId) {
      errors.buildingId = 'Building selection is required.';
    }

    if (!formData.apartmentId) {
      errors.apartmentId = 'Available apartment unit selection is required.';
    }

    // Household size validation: integer 1–15
    const isHouseholdNumeric = /^\d+$/.test(String(formData.householdSize).trim());
    if (formData.householdSize === '' || formData.householdSize === null || !isHouseholdNumeric) {
      errors.householdSize = 'Total people is required and must be an integer between 1 and 15.';
    } else {
      const hh = parseInt(formData.householdSize, 10);
      if (hh < 1 || hh > 15) {
        errors.householdSize = 'Total people must be between 1 and 15.';
      }
    }

    // Children count validation: integer 0 to householdSize
    const isChildrenNumeric = /^\d+$/.test(String(formData.childrenCount).trim());
    if (formData.childrenCount === '' || formData.childrenCount === null || !isChildrenNumeric) {
      errors.childrenCount = 'Children count is required (enter 0 if no children under 12).';
    } else {
      const ch = parseInt(formData.childrenCount, 10);
      if (ch < 0) {
        errors.childrenCount = 'Children count cannot be negative.';
      } else if (isHouseholdNumeric) {
        const hh = parseInt(formData.householdSize, 10);
        if (ch > hh) {
          errors.childrenCount = 'Children (under 12) cannot exceed total people.';
        }
      }
    }

    setFieldErrors(errors);
    return errors;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');

    const validationErrors = validate();
    if (Object.keys(validationErrors).length > 0) {
      setError('Please fill in all mandatory fields with valid values.');
      return;
    }

    setLoading(true);

    try {
      const payload = {
        name: formData.name.trim(),
        email: formData.email.trim(),
        phone: formData.phone.trim(),
        password: formData.password,
        confirmPassword: formData.confirmPassword,
        buildingId: parseInt(formData.buildingId, 10),
        apartmentId: parseInt(formData.apartmentId, 10),
        householdSize: parseInt(formData.householdSize, 10),
        childrenCount: parseInt(formData.childrenCount, 10)
      };

      const res = await registerResident(payload);
      if (res && res.token) {
        navigate('/resident/dashboard');
      } else {
        setSuccessMsg(res?.message || 'Registration submitted successfully! Your account is pending Building Owner approval. Once approved, you can sign in.');
      }
    } catch (err) {
      console.error('Resident registration failed:', err);
      const msg = err.response?.data?.message || err.response?.data?.error || err.message || 'Registration failed. Please check your inputs.';
      setError(msg);
      if (err.response?.data?.errors) {
        setFieldErrors(prev => ({ ...prev, ...err.response.data.errors }));
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-vh-100 py-5" style={{ backgroundColor: '#F0FAFF' }}>
      <div className="container" style={{ maxWidth: 640 }}>
        
        {/* Brand Header */}
        <div className="text-center mb-4">
          <div className="d-inline-flex align-items-center justify-content-center rounded-circle mb-3 shadow-sm"
               style={{ width: '56px', height: '56px', backgroundColor: '#005F73', color: '#fff' }}>
            <i className="bi bi-person-plus-fill fs-3"></i>
          </div>
          <h3 className="fw-bold mb-1" style={{ color: '#003554' }}>Resident Registration</h3>
          <p className="text-muted fs-7">Create your resident profile to track water usage, view invoices, and manage your unit.</p>
        </div>

        {/* Form Card */}
        <div className="card glass-card border-0 shadow-lg p-4 p-md-5 rounded-4 bg-white">
          {error && (
            <div className="alert alert-danger py-2.5 px-3 small rounded-3 mb-4 d-flex align-items-center gap-2">
              <i className="bi bi-exclamation-triangle-fill fs-6 flex-shrink-0"></i>
              <div>{error}</div>
            </div>
          )}

          {successMsg ? (
            <div className="py-3 text-center">
              <div className="rounded-circle d-inline-flex align-items-center justify-content-center mb-3 shadow-sm" style={{ width: 64, height: 64, backgroundColor: '#E8F5E9', color: '#2E7D32' }}>
                <i className="bi bi-check-circle-fill fs-1"></i>
              </div>
              <h4 className="fw-bold text-dark mb-2">Registration Submitted</h4>
              <p className="text-muted mb-4 fs-7 px-md-3">
                {successMsg}
              </p>
              <div className="d-flex justify-content-center gap-2">
                <Link to="/login" className="btn px-4 py-2 text-white fw-bold shadow-sm" style={{ backgroundColor: '#005F73' }}>
                  Proceed to Sign In <i className="bi bi-box-arrow-in-right ms-1"></i>
                </Link>
              </div>
            </div>
          ) : (
          <form onSubmit={handleSubmit} noValidate>
            <div className="row g-3 mb-3">
              <div className="col-12">
                <label className="form-label fw-semibold fs-7" style={{ color: '#003554' }}>
                  Full Name <span className="text-danger">*</span>
                </label>
                <div className="input-group">
                  <span className="input-group-text bg-white border-end-0 text-muted" style={{ borderColor: fieldErrors.name ? '#dc3545' : '#D4EBF8' }}>
                    <i className="bi bi-person"></i>
                  </span>
                  <input
                    type="text"
                    name="name"
                    className={`form-control border-start-0 ps-0 ${fieldErrors.name ? 'is-invalid' : ''}`}
                    style={{ borderColor: fieldErrors.name ? '#dc3545' : '#D4EBF8' }}
                    placeholder="e.g. John Doe"
                    value={formData.name}
                    onChange={handleChange}
                    required
                  />
                </div>
                {fieldErrors.name && (
                  <div className="text-danger fs-8 mt-1">{fieldErrors.name}</div>
                )}
              </div>

              <div className="col-md-6">
                <label className="form-label fw-semibold fs-7" style={{ color: '#003554' }}>
                  Email Address <span className="text-danger">*</span>
                </label>
                <div className="input-group">
                  <span className="input-group-text bg-white border-end-0 text-muted" style={{ borderColor: fieldErrors.email ? '#dc3545' : '#D4EBF8' }}>
                    <i className="bi bi-envelope"></i>
                  </span>
                  <input
                    type="email"
                    name="email"
                    className={`form-control border-start-0 ps-0 ${fieldErrors.email ? 'is-invalid' : ''}`}
                    style={{ borderColor: fieldErrors.email ? '#dc3545' : '#D4EBF8' }}
                    placeholder="john@example.com"
                    value={formData.email}
                    onChange={handleChange}
                    required
                  />
                </div>
                {fieldErrors.email && (
                  <div className="text-danger fs-8 mt-1">{fieldErrors.email}</div>
                )}
              </div>

              <div className="col-md-6">
                <label className="form-label fw-semibold fs-7" style={{ color: '#003554' }}>
                  Phone Number <span className="text-danger">*</span>
                </label>
                <div className="input-group">
                  <span className="input-group-text bg-white border-end-0 text-muted" style={{ borderColor: fieldErrors.phone ? '#dc3545' : '#D4EBF8' }}>
                    <i className="bi bi-telephone"></i>
                  </span>
                  <input
                    type="tel"
                    name="phone"
                    className={`form-control border-start-0 ps-0 ${fieldErrors.phone ? 'is-invalid' : ''}`}
                    style={{ borderColor: fieldErrors.phone ? '#dc3545' : '#D4EBF8' }}
                    placeholder="9876543210"
                    value={formData.phone}
                    onChange={handleChange}
                    required
                  />
                </div>
                {fieldErrors.phone && (
                  <div className="text-danger fs-8 mt-1">{fieldErrors.phone}</div>
                )}
              </div>

              <div className="col-md-6">
                <label className="form-label fw-semibold fs-7" style={{ color: '#003554' }}>
                  Password <span className="text-danger">*</span>
                </label>
                <div className="input-group">
                  <span className="input-group-text bg-white border-end-0 text-muted" style={{ borderColor: fieldErrors.password ? '#dc3545' : '#D4EBF8' }}>
                    <i className="bi bi-lock"></i>
                  </span>
                  <input
                    type="password"
                    name="password"
                    className={`form-control border-start-0 ps-0 ${fieldErrors.password ? 'is-invalid' : ''}`}
                    style={{ borderColor: fieldErrors.password ? '#dc3545' : '#D4EBF8' }}
                    placeholder="At least 6 characters"
                    value={formData.password}
                    onChange={handleChange}
                    minLength={6}
                    required
                  />
                </div>
                {fieldErrors.password && (
                  <div className="text-danger fs-8 mt-1">{fieldErrors.password}</div>
                )}
              </div>

              <div className="col-md-6">
                <label className="form-label fw-semibold fs-7" style={{ color: '#003554' }}>
                  Confirm Password <span className="text-danger">*</span>
                </label>
                <div className="input-group">
                  <span className="input-group-text bg-white border-end-0 text-muted" style={{ borderColor: fieldErrors.confirmPassword ? '#dc3545' : '#D4EBF8' }}>
                    <i className="bi bi-lock-fill"></i>
                  </span>
                  <input
                    type="password"
                    name="confirmPassword"
                    className={`form-control border-start-0 ps-0 ${fieldErrors.confirmPassword ? 'is-invalid' : ''}`}
                    style={{ borderColor: fieldErrors.confirmPassword ? '#dc3545' : '#D4EBF8' }}
                    placeholder="Repeat password"
                    value={formData.confirmPassword}
                    onChange={handleChange}
                    minLength={6}
                    required
                  />
                </div>
                {fieldErrors.confirmPassword && (
                  <div className="text-danger fs-8 mt-1">{fieldErrors.confirmPassword}</div>
                )}
              </div>
            </div>

            {/* Mandatory Building & Apartment Selection Section */}
            <div className="p-3.5 rounded-4 mb-4 border" style={{ backgroundColor: '#F8FCFD', borderColor: '#D4EBF8' }}>
              <div className="d-flex align-items-center gap-2 mb-2">
                <div className="d-inline-flex align-items-center justify-content-center rounded-circle"
                     style={{ width: '28px', height: '28px', backgroundColor: '#E0F2FE', color: '#005F73' }}>
                  <i className="bi bi-building fs-7"></i>
                </div>
                <h6 className="fw-bold mb-0" style={{ color: '#003554' }}>
                  Building & Apartment Unit Selection <span className="text-danger">*</span>
                </h6>
              </div>
              <p className="text-muted fs-8 mb-3">
                Select your building and an available apartment unit to connect your profile to your water meter.
              </p>

              <div className="row g-3">
                <div className="col-md-6">
                  <label className="form-label fs-8 fw-semibold" style={{ color: '#003554' }}>
                    Building <span className="text-danger">*</span>
                  </label>
                  <select
                    name="buildingId"
                    className={`form-select form-select-sm ${fieldErrors.buildingId ? 'is-invalid' : ''}`}
                    value={formData.buildingId}
                    onChange={handleBuildingChange}
                    required
                  >
                    <option value="">-- Choose Building --</option>
                    {buildingsList.map(b => (
                      <option key={b.buildingId} value={b.buildingId}>
                        {b.buildingName} {b.location ? `(${b.location})` : ''} {b.ownerName ? `• Owner: ${b.ownerName}` : ''}
                      </option>
                    ))}
                  </select>
                  {fieldErrors.buildingId && (
                    <div className="invalid-feedback d-block fs-8">{fieldErrors.buildingId}</div>
                  )}
                </div>

                <div className="col-md-6">
                  <label className="form-label fs-8 fw-semibold" style={{ color: '#003554' }}>
                    Available Apartment Unit <span className="text-danger">*</span>
                  </label>
                  <select
                    name="apartmentId"
                    className={`form-select form-select-sm ${fieldErrors.apartmentId ? 'is-invalid' : ''}`}
                    value={formData.apartmentId}
                    onChange={handleChange}
                    required
                    disabled={!formData.buildingId || loadingApts}
                  >
                    <option value="">
                      {!formData.buildingId
                        ? '-- Choose Building First --'
                        : loadingApts
                        ? 'Loading units...'
                        : availableApartments.length === 0
                        ? '-- No Available Units --'
                        : '-- Choose Unit --'}
                    </option>
                    {availableApartments.map(a => (
                      <option key={a.apartmentId} value={a.apartmentId}>
                        Floor {a.floorNumber} - Unit {a.roomNumber} ({a.unitType || 'Flat'})
                      </option>
                    ))}
                  </select>
                  {fieldErrors.apartmentId && (
                    <div className="invalid-feedback d-block fs-8">{fieldErrors.apartmentId}</div>
                  )}
                  {loadingApts && (
                    <small className="text-muted d-block mt-1 fs-8">
                      <span className="spinner-border spinner-border-sm me-1" style={{ width: '0.75rem', height: '0.75rem' }}></span>
                      Loading available units...
                    </small>
                  )}
                  {!loadingApts && formData.buildingId && availableApartments.length === 0 && (
                    <small className="text-danger d-block mt-1 fs-8 fw-semibold">
                      No vacant units available in this building.
                    </small>
                  )}
                </div>

                {/* Mandatory Household Details for Water Diagnostics */}
                <div className="col-12 mt-2 pt-2 border-top" style={{ borderColor: '#E5F3F9' }}>
                  <div className="d-flex align-items-center gap-2 mb-1">
                    <i className="bi bi-people-fill" style={{ color: '#005F73' }}></i>
                    <label className="form-label fs-8 fw-bold mb-0" style={{ color: '#003554' }}>
                      Household Details for Water Diagnostics <span className="text-danger">*</span>
                    </label>
                  </div>
                  <p className="text-muted fs-8 mb-2">
                    Compulsory for water diagnostics and baseline anomaly detection. Enter whole numbers only.
                  </p>
                  <div className="row g-2">
                    <div className="col-6">
                      <label className="form-label fs-8 text-muted mb-1">
                        Total People (1–15) <span className="text-danger">*</span>
                      </label>
                      <input
                        type="number"
                        min="1"
                        max="15"
                        step="1"
                        name="householdSize"
                        className={`form-control form-control-sm ${fieldErrors.householdSize ? 'is-invalid' : ''}`}
                        placeholder="1 to 15"
                        value={formData.householdSize}
                        onChange={handleChange}
                        onKeyDown={(e) => {
                          if (['e', 'E', '+', '-', '.'].includes(e.key)) e.preventDefault();
                        }}
                        required
                      />
                      {fieldErrors.householdSize && (
                        <div className="invalid-feedback d-block fs-8">{fieldErrors.householdSize}</div>
                      )}
                    </div>
                    <div className="col-6">
                      <label className="form-label fs-8 text-muted mb-1">
                        Children (Under 12) <span className="text-danger">*</span>
                      </label>
                      <input
                        type="number"
                        min="0"
                        max={formData.householdSize || 15}
                        step="1"
                        name="childrenCount"
                        className={`form-control form-control-sm ${fieldErrors.childrenCount ? 'is-invalid' : ''}`}
                        placeholder="0 or more"
                        value={formData.childrenCount}
                        onChange={handleChange}
                        onKeyDown={(e) => {
                          if (['e', 'E', '+', '-', '.'].includes(e.key)) e.preventDefault();
                        }}
                        required
                      />
                      {fieldErrors.childrenCount && (
                        <div className="invalid-feedback d-block fs-8">{fieldErrors.childrenCount}</div>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <button
              type="submit"
              className="btn w-100 py-2.5 fw-bold text-white shadow-sm"
              style={{ backgroundColor: '#005F73', borderColor: '#005F73' }}
              disabled={loading}
            >
              {loading ? (
                <span><span className="spinner-border spinner-border-sm me-2"></span>Creating Profile...</span>
              ) : (
                <span>Register Resident Profile <i className="bi bi-arrow-right ms-2"></i></span>
              )}
            </button>
          </form>
          )}

          {/* Footer Navigation */}
          <div className="mt-4 pt-3 border-top text-center" style={{ borderColor: '#E8F4FA' }}>
            <span className="text-muted fs-7">Already registered? </span>
            <Link to="/login" className="text-decoration-none fw-bold fs-7" style={{ color: '#005F73' }}>
              Sign In to Your Account <i className="bi bi-box-arrow-in-right ms-1"></i>
            </Link>
          </div>
        </div>

      </div>
    </div>
  );
}
