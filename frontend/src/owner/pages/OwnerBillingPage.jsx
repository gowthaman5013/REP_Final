import React, { useState, useEffect } from 'react';
import { Link } from 'react-router-dom';
import API from '../../shared/services/api';
import LoadingSpinner from '../../shared/components/LoadingSpinner';

export default function OwnerBillingPage() {
  const [activeTab, setActiveTab] = useState('CYCLES'); // 'CYCLES', 'PURCHASES', 'TARIFF', 'INVOICES'
  const [buildings, setBuildings] = useState([]);
  const [selectedBuildingId, setSelectedBuildingId] = useState('');

  const [bills, setBills] = useState([]);
  const [cycles, setCycles] = useState([]);
  const [selectedCycleId, setSelectedCycleId] = useState('');
  const [tariff, setTariff] = useState(null);
  const [purchases, setPurchases] = useState([]);
  const [summary, setSummary] = useState({ totalQuantity: 0, totalCost: 0, averagePurchaseCost: 0 });
  const [loading, setLoading] = useState(true);

  // Forms State
  const [purchaseForm, setPurchaseForm] = useState({ supplier: 'Municipal Water Supply', volume: '1000', unitCost: '50' });
  const [cycleForm, setCycleForm] = useState({
    startDate: new Date().toISOString().substring(0, 7) + '-01',
    endDate: new Date(Date.now() + 30 * 86400000).toISOString().substring(0, 10)
  });
  const [markupInput, setMarkupInput] = useState('20');
  const [cycleSuccessBanner, setCycleSuccessBanner] = useState('');

  // Read URL query parameter for tab selection (e.g. ?tab=purchases)
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const tabParam = params.get('tab');
    if (tabParam) {
      const upper = tabParam.toUpperCase();
      if (['CYCLES', 'PURCHASES', 'TARIFF', 'INVOICES'].includes(upper)) {
        setActiveTab(upper);
      }
    }
  }, []);

  // Fetch Owner's Buildings on mount
  useEffect(() => {
    API.get('/owners/buildings')
      .then(res => {
        const bList = res.data || [];
        setBuildings(bList);
        if (bList.length > 0) {
          setSelectedBuildingId(bList[0].buildingId);
        }
      })
      .catch(err => console.error('Failed to load buildings', err));
  }, []);

  const fetchData = (bId, preferredCycleId) => {
    const targetBuildingId = bId || selectedBuildingId;
    if (!targetBuildingId) return;

    setLoading(true);

    Promise.all([
      API.get(`/api/owner/billing-cycles?buildingId=${targetBuildingId}`).catch(() => API.get('/api/billing-cycles')),
      API.get('/bills/owner')
    ]).then(([cycleRes, billRes]) => {
      const cList = cycleRes.data || [];
      setCycles(cList);
      setBills(billRes.data || []);

      if (cList.length > 0) {
        // Auto calculate default dates for next cycle creation
        const latestCycle = cList[0];
        if (latestCycle.endDate) {
          const nextStart = new Date(latestCycle.endDate);
          nextStart.setDate(nextStart.getDate() + 1);
          const nextEnd = new Date(nextStart);
          nextEnd.setMonth(nextEnd.getMonth() + 1);
          nextEnd.setDate(0);

          setCycleForm({
            startDate: nextStart.toISOString().substring(0, 10),
            endDate: nextEnd.toISOString().substring(0, 10)
          });
        }

        const activeCycleId = preferredCycleId || selectedCycleId || cList[0].id;
        setSelectedCycleId(activeCycleId);
        fetchCycleData(targetBuildingId, activeCycleId);
      } else {
        setLoading(false);
      }
    }).catch(err => {
      console.error('Error fetching billing data:', err);
      setLoading(false);
    });
  };

  const fetchCycleData = (bId, cId) => {
    if (!cId) {
      setLoading(false);
      return;
    }
    Promise.all([
      API.get(`/api/water-purchases/cycle/${cId}`).catch(() => API.get(`/api/water-purchases/building/${bId}`)),
      API.get(`/api/tariff-plans/building/${bId}?billingCycleId=${cId}`).catch(() => API.get('/api/admin/tariff'))
    ]).then(([purchaseRes, tariffRes]) => {
      const pList = purchaseRes.data || [];
      setPurchases(pList);
      const tPlan = tariffRes.data || null;
      setTariff(tPlan);
      if (tPlan && tPlan.markupPercentage !== undefined) {
        setMarkupInput(String(tPlan.markupPercentage));
      }

      let totQty = 0;
      let totCost = 0;
      pList.forEach(p => {
        const qty = parseFloat(p.volume || p.quantity || 0);
        const cost = parseFloat(p.totalCost || (qty * (p.unitCost || 0)));
        totQty += qty;
        totCost += cost;
      });

      const avgCost = totQty > 0 ? (totCost / totQty) : (tPlan?.averageProcurementCost ? parseFloat(tPlan.averageProcurementCost) : 0);
      setSummary({
        totalQuantity: totQty,
        totalCost: totCost,
        averagePurchaseCost: avgCost
      });
    }).catch(err => console.error(err))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (selectedBuildingId) {
      fetchData(selectedBuildingId);
    } else {
      setLoading(false);
    }
  }, [selectedBuildingId]);

  const handleBuildingChange = (e) => {
    const bId = e.target.value;
    setSelectedBuildingId(bId);
    setSelectedCycleId('');
    fetchData(bId, null);
  };

  const handleCycleSelectChange = (e) => {
    const cId = e.target.value;
    setSelectedCycleId(cId);
    fetchCycleData(selectedBuildingId, cId);
  };

  const handleRecordPurchase = async (e) => {
    e.preventDefault();
    if (!selectedCycleId) {
      alert('Please select a billing cycle first!');
      return;
    }
    try {
      await API.post('/api/water-purchases', {
        ...purchaseForm,
        buildingId: selectedBuildingId,
        cycleId: selectedCycleId,
        billingCycleId: selectedCycleId
      });
      alert('Bulk water purchase recorded! Tariff dynamically updated.');
      setPurchaseForm({ supplier: 'Water Tanker Vendor', volume: '500', unitCost: '65' });
      fetchData(selectedBuildingId, selectedCycleId);
    } catch (err) {
      alert(err.response?.data?.message || 'Failed to record purchase');
    }
  };

  const handleDeletePurchase = async (pId) => {
    if (!window.confirm('Delete this bulk purchase record? Dynamic tariff will recalculate.')) return;
    try {
      await API.delete(`/api/water-purchases/${pId}`);
      alert('Water purchase deleted! Dynamic tariff recalculated.');
      fetchData(selectedBuildingId, selectedCycleId);
    } catch (err) {
      alert(err.response?.data?.message || 'Delete purchase failed');
    }
  };

  const handleUpdateMarkup = async (e) => {
    e.preventDefault();
    if (!selectedCycleId) {
      alert('Please select a billing cycle first!');
      return;
    }
    try {
      await API.post(`/api/tariff-plans/cycle/${selectedCycleId}/markup`, {
        markupPercentage: markupInput
      });
      alert(`Markup percentage updated to ${markupInput}%! Resident tariff recalculated.`);
      fetchData(selectedBuildingId, selectedCycleId);
    } catch (err) {
      alert(err.response?.data?.message || 'Failed to update markup percentage');
    }
  };

  const [isCreatingCycle, setIsCreatingCycle] = useState(false);

  const handleOpenCycle = async (e) => {
    e.preventDefault();
    if (!selectedBuildingId) {
      alert('Please select a building before creating a billing cycle.');
      return;
    }

    const currentBuilding = buildings.find(b => String(b.buildingId) === String(selectedBuildingId));
    const bName = currentBuilding ? currentBuilding.buildingName : `Building #${selectedBuildingId}`;

    const confirmMsg = `CONFIRM BILLING CYCLE CREATION\n\n` +
      `Target Building: ${bName} (ID: ${selectedBuildingId})\n` +
      `Period: ${cycleForm.startDate} to ${cycleForm.endDate}\n\n` +
      `Important Workflow Step:\n` +
      `After creating this billing cycle, you will be automatically navigated to "2. Bulk Water Purchases" to record bulk water purchases (tankers/municipal supply) for this cycle to calculate the dynamic 3-tier tariff before issuing bills.\n\n` +
      `Proceed with creating this billing cycle?`;

    if (!window.confirm(confirmMsg)) {
      return;
    }

    setIsCreatingCycle(true);
    try {
      const res = await API.post('/api/owner/billing-cycles', {
        ...cycleForm,
        buildingId: selectedBuildingId
      });

      const newCycle = res.data;
      setSelectedCycleId(newCycle.id);
      fetchData(selectedBuildingId, newCycle.id);
      setActiveTab('PURCHASES');
      setCycleSuccessBanner(
        `Billing cycle #${newCycle.id} (${newCycle.startDate} to ${newCycle.endDate}) created successfully! First create the bulk water purchase for this billing cycle below to set up procurement costs and calculate 3-tier tariffs.`
      );
    } catch (err) {
      alert(err.response?.data?.message || 'Failed to create billing cycle.');
    } finally {
      setIsCreatingCycle(false);
    }
  };

  const [finalizingCycleId, setFinalizingCycleId] = useState(null);

  const handleFinalizeCycle = async (cycleId) => {
    const cycle = cycles.find(c => String(c.id) === String(cycleId));

    const cyclePurchases = String(cycleId) === String(selectedCycleId) ? purchases : [];
    if (cyclePurchases.length === 0) {
      const wantPurchase = window.confirm(
        `⚠️ Bulk Water Purchases Advisory:\n\n` +
        `Billing Cycle #${cycleId} currently has 0 bulk water purchases recorded.\n` +
        `Without recorded water purchases, the average procurement cost will be ₹0.00.\n\n` +
        `Click "OK" to navigate to "2. Bulk Water Purchases" to record purchases first.\n` +
        `Click "Cancel" to proceed with finalization anyway.`
      );
      if (wantPurchase) {
        setSelectedCycleId(cycleId);
        fetchCycleData(selectedBuildingId, cycleId);
        setActiveTab('PURCHASES');
        return;
      }
    }

    const confirmMsg = `Are you sure you want to finalize Billing Cycle #${cycleId} (${cycle?.startDate} to ${cycle?.endDate})?\n\n` +
      `Once finalized:\n` +
      `• Meter readings cannot be modified\n` +
      `• 3-Tier progressive tariff bills will be calculated\n` +
      `• Invoices and Bills will be generated for all residents\n` +
      `• In-app notifications & billing emails will be sent\n\n` +
      `Proceed with finalization?`;

    if (!window.confirm(confirmMsg)) return;

    setFinalizingCycleId(cycleId);
    try {
      await API.post(`/api/owner/billing-cycles/${cycleId}/finalize`);
      alert(`✓ Billing cycle #${cycleId} finalized successfully!\n\n` +
        `Summary of actions completed:\n` +
        `• Status updated: FINALIZED\n` +
        `• 3-Tier Tariff calculated: Yes\n` +
        `• Invoices generated: All active residents\n` +
        `• Bills generated: All active residents\n` +
        `• Notifications created: Yes\n` +
        `• Email notifications dispatched: Yes`);
      fetchData(selectedBuildingId, cycleId);
      setActiveTab('INVOICES');
    } catch (err) {
      alert(err.response?.data?.message || 'Finalization failed. Please verify meter readings are recorded for all residents.');
    } finally {
      setFinalizingCycleId(null);
    }
  };

  const handleArchiveCycle = async (cycleId) => {
    try {
      await API.post(`/api/owner/billing-cycles/${cycleId}/archive`);
      alert('Billing cycle archived!');
      fetchData(selectedBuildingId, cycleId);
    } catch (err) {
      alert(err.response?.data?.message || 'Archive failed');
    }
  };

  if (loading) return <LoadingSpinner text="Fetching billing engine controls..." />;

  const currentCycle = cycles.find(c => String(c.id) === String(selectedCycleId));
  const currentBuilding = buildings.find(b => String(b.buildingId) === String(selectedBuildingId));
  const isFinalized = currentCycle?.status === 'FINALIZED' || currentCycle?.status === 'ARCHIVED';

  const averageProcurement = tariff?.averageProcurementCost !== undefined ? parseFloat(tariff.averageProcurementCost) : summary.averagePurchaseCost;
  const markupPct = tariff?.markupPercentage !== undefined ? parseFloat(tariff.markupPercentage) : 20.0;
  const baseTariffVal = tariff?.baseTariff !== undefined ? parseFloat(tariff.baseTariff) : (averageProcurement * (1 + markupPct / 100));

  const tier1MultiplierVal = tariff?.tier1Multiplier !== undefined ? parseFloat(tariff.tier1Multiplier) : 1.00;
  const tier2MultiplierVal = tariff?.tier2Multiplier !== undefined ? parseFloat(tariff.tier2Multiplier) : 1.25;
  const tier3MultiplierVal = tariff?.tier3Multiplier !== undefined ? parseFloat(tariff.tier3Multiplier) : 1.50;

  const tier1Limit = tariff?.tier1Limit !== undefined ? tariff.tier1Limit : 20.0;
  const tier1Rate = tariff?.tier1Rate !== undefined ? parseFloat(tariff.tier1Rate) : (baseTariffVal * tier1MultiplierVal);
  const tier2Limit = tariff?.tier2Limit !== undefined ? tariff.tier2Limit : 40.0;
  const tier2Rate = tariff?.tier2Rate !== undefined ? parseFloat(tariff.tier2Rate) : (baseTariffVal * tier2MultiplierVal);
  const tier3Rate = tariff?.tier3Rate !== undefined ? parseFloat(tariff.tier3Rate) : (baseTariffVal * tier3MultiplierVal);

  return (
    <div className="container-fluid py-4">
      <div className="d-flex align-items-center justify-content-between mb-4 flex-wrap gap-3">
        <div>
          <h2 className="fw-bold mb-1 text-dark">Billing Engine & Distribution Control</h2>
          <p className="text-muted small mb-0">Manage 3-tier tariffs, bulk water purchases, consumption distribution & monthly cycles</p>
        </div>

        {/* Building & Cycle Selectors */}
        <div className="d-flex align-items-center gap-3">
          {buildings.length > 0 && (
            <div className="d-flex align-items-center bg-white p-2 rounded-3 shadow-sm border">
              <i className="bi bi-building text-secondary me-2 ms-2 fs-6"></i>
              <span className="fw-semibold me-2 text-dark fs-7">Building:</span>
              <select
                className="form-select border-0 bg-light fw-bold text-dark fs-7"
                style={{ minWidth: '200px' }}
                value={selectedBuildingId}
                onChange={handleBuildingChange}
              >
                {buildings.map(b => (
                  <option key={b.buildingId} value={b.buildingId}>
                    {b.buildingName} ({b.buildingNumber})
                  </option>
                ))}
              </select>
            </div>
          )}

          {cycles.length > 0 && (
            <div className="d-flex align-items-center bg-white p-2 rounded-3 shadow-sm border">
              <i className="bi bi-calendar-event text-secondary me-2 ms-2 fs-6"></i>
              <span className="fw-semibold me-2 text-dark fs-7">Cycle:</span>
              <select
                className="form-select border-0 bg-light fw-bold text-dark fs-7"
                style={{ minWidth: '220px' }}
                value={selectedCycleId}
                onChange={handleCycleSelectChange}
              >
                {cycles.map(c => (
                  <option key={c.id} value={c.id}>
                    Cycle #{c.id}: {c.startDate} to {c.endDate} ({c.status})
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>
      </div>

      {isFinalized && (
        <div className="alert alert-secondary border border-secondary-subtle d-flex align-items-center gap-2 mb-4 rounded-3 shadow-sm py-2.5 px-3">
          <i className="bi bi-lock-fill fs-5 text-dark"></i>
          <div>
            <strong className="text-dark">🔒 Billing Cycle Finalized & Locked:</strong>
            <span className="text-muted ms-1 fs-7">Bulk purchases, markup %, tariff plan, and resident bills for Cycle #{selectedCycleId} are permanently locked against modifications.</span>
          </div>
        </div>
      )}

      {/* Tabs Header */}
      <div className="d-flex align-items-center justify-content-between mb-4 flex-wrap gap-2">
        <ul className="nav nav-pills gap-2 mb-0">
          <li className="nav-item">
            <button className={`nav-link fw-bold ${activeTab === 'CYCLES' ? 'active' : 'bg-white text-dark border'}`} onClick={() => setActiveTab('CYCLES')}>
              <i className="bi bi-arrow-repeat me-1.5"></i> 1. Billing Cycles
            </button>
          </li>
        <li className="nav-item">
          <button className={`nav-link fw-bold ${activeTab === 'PURCHASES' ? 'active' : 'bg-white text-dark border'}`} onClick={() => setActiveTab('PURCHASES')}>
            <i className="bi bi-truck me-1.5"></i> 2. Bulk Water Purchases
          </button>
        </li>
        <li className="nav-item">
          <button className={`nav-link fw-bold ${activeTab === 'TARIFF' ? 'active' : 'bg-white text-dark border'}`} onClick={() => setActiveTab('TARIFF')}>
            <i className="bi bi-table me-1.5"></i> 3. Tiered Tariff
          </button>
        </li>
        <li className="nav-item">
          <button className={`nav-link fw-bold ${activeTab === 'INVOICES' ? 'active' : 'bg-white text-dark border'}`} onClick={() => setActiveTab('INVOICES')}>
            <i className="bi bi-receipt-cutoff me-1.5"></i> 4. Resident Monthly Bills
          </button>
        </li>
      </ul>

      <Link to="/owner/bulk-billing" className="btn btn-outline-primary fw-bold d-flex align-items-center gap-2 shadow-sm">
        <i className="bi bi-file-earmark-spreadsheet-fill text-primary fs-6"></i> Bulk Import Readings (CSV)
      </Link>
    </div>

      {/* Tab 1: Billing Cycles */}
      {activeTab === 'CYCLES' && (
        <div className="row g-4">
          <div className="col-lg-4">
            <div className="card glass-card border-0 p-4 shadow-sm">
              <div className="d-flex align-items-center justify-content-between mb-3">
                <h5 className="fw-bold mb-0"><i className="bi bi-plus-circle-fill text-primary me-2"></i>Open Billing Cycle</h5>
                <span className="badge bg-primary-subtle text-primary border border-primary-subtle">Safeguarded</span>
              </div>

              {/* Explicit Target Building Information */}
              <div className="mb-3 p-3 bg-light rounded-3 border">
                <div className="text-muted small fw-semibold text-uppercase mb-1" style={{ fontSize: '0.72rem', letterSpacing: '0.5px' }}>
                  <i className="bi bi-building me-1"></i> Target Building
                </div>
                <div className="fw-bold text-dark fs-6">
                  {currentBuilding ? currentBuilding.buildingName : (selectedBuildingId ? `Building #${selectedBuildingId}` : 'None Selected')}
                </div>
                <div className="text-muted fs-7">
                  Building ID: <strong className="text-dark">#{selectedBuildingId || 'N/A'}</strong>
                  {currentBuilding?.buildingNumber && ` • Tower ${currentBuilding.buildingNumber}`}
                </div>
              </div>

              {/* Informative Guidance Banner on Creating Billing Cycle */}
              <div className="alert alert-info py-2.5 px-3 small mb-3 border border-info-subtle rounded-3">
                <div className="d-flex align-items-start gap-2">
                  <i className="bi bi-info-circle-fill text-info fs-5 mt-0.5"></i>
                  <div>
                    <strong className="d-block mb-1 text-dark">Workflow Step 1 of 4:</strong>
                    First create the billing cycle here. Once created, you will be automatically navigated to <strong>2. Bulk Water Purchases</strong> to record water purchases (vendor, volume &amp; unit cost) for this billing cycle.
                  </div>
                </div>
              </div>

              <form onSubmit={handleOpenCycle}>
                <div className="mb-3">
                  <label className="form-label fw-semibold fs-7 text-dark">Cycle Start Date *</label>
                  <input 
                    type="date" 
                    className="form-control" 
                    value={cycleForm.startDate} 
                    onChange={e => setCycleForm({...cycleForm, startDate: e.target.value})} 
                    required 
                    disabled={isCreatingCycle}
                  />
                </div>
                <div className="mb-3">
                  <label className="form-label fw-semibold fs-7 text-dark">Cycle End Date *</label>
                  <input 
                    type="date" 
                    className="form-control" 
                    value={cycleForm.endDate} 
                    onChange={e => setCycleForm({...cycleForm, endDate: e.target.value})} 
                    required 
                    disabled={isCreatingCycle}
                  />
                </div>

                <div className="alert alert-warning py-2.5 px-3 small mb-3 border border-warning-subtle rounded-3">
                  <div className="d-flex align-items-start gap-2">
                    <i className="bi bi-shield-exclamation text-warning-emphasis fs-6 mt-0.5"></i>
                    <div>
                      <strong>Auto-Navigation:</strong> You will be prompted to confirm dates. Upon creation, you will automatically be directed to <strong>Bulk Water Purchases</strong> to record procurement.
                    </div>
                  </div>
                </div>

                <button 
                  type="submit" 
                  className="btn btn-primary w-100 fw-bold py-2 shadow-sm"
                  disabled={isCreatingCycle || !selectedBuildingId}
                >
                  {isCreatingCycle ? (
                    <>
                      <span className="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>
                      Creating Cycle...
                    </>
                  ) : (
                    <>
                      <i className="bi bi-folder-plus me-1.5"></i> Create Billing Cycle
                    </>
                  )}
                </button>
              </form>

              {/* Direct Navigation Button to Bulk Water Purchases */}
              {selectedCycleId && (
                <div className="mt-3 pt-3 border-top text-center">
                  <button
                    type="button"
                    className={`btn ${isFinalized ? 'btn-outline-secondary' : 'btn-outline-primary'} btn-sm w-100 fw-semibold d-flex align-items-center justify-content-center gap-1.5`}
                    onClick={() => setActiveTab('PURCHASES')}
                  >
                    <i className={`bi ${isFinalized ? 'bi-eye' : 'bi-truck'}`}></i> Navigate to Bulk Water Purchases {isFinalized ? '(Read-Only) ' : ''}for Cycle #{selectedCycleId} →
                  </button>
                </div>
              )}
            </div>
          </div>

          <div className="col-lg-8">
            <div className="card glass-card border-0 p-4 shadow-sm">
              <h5 className="fw-bold mb-3 text-dark">Billing Cycle Management</h5>
              <div className="table-responsive">
                <table className="table custom-table align-middle mb-0">
                  <thead>
                    <tr>
                      <th>CYCLE ID</th>
                      <th>PERIOD</th>
                      <th>STATUS</th>
                      <th>BULK WATER</th>
                      <th>ACTIONS</th>
                    </tr>
                  </thead>
                  <tbody>
                    {cycles.length ? (
                      cycles.map(c => (
                        <tr key={c.id} className={String(c.id) === String(selectedCycleId) ? 'table-active fw-bold' : ''}>
                          <td><code className="text-dark fw-bold">#{c.id}</code></td>
                          <td className="fw-semibold">{c.startDate} to {c.endDate}</td>
                          <td>
                            <span className={`badge ${c.status === 'OPEN' ? 'bg-success-subtle text-success border border-success-subtle' : c.status === 'FINALIZED' ? 'bg-secondary-subtle text-dark border' : 'bg-light text-muted border'}`}>
                              {c.status}
                            </span>
                          </td>
                          <td>
                            <button
                              className={`btn btn-sm ${c.status === 'OPEN' ? 'btn-outline-primary' : 'btn-outline-secondary'} py-1 px-2.5 fs-8 fw-semibold d-flex align-items-center gap-1`}
                              onClick={() => {
                                setSelectedCycleId(c.id);
                                fetchCycleData(selectedBuildingId, c.id);
                                setActiveTab('PURCHASES');
                              }}
                              title={c.status === 'OPEN' ? 'Navigate to Bulk Water Purchases for this cycle' : 'View Bulk Water Purchases (Read-Only)'}
                            >
                              <i className={`bi ${c.status === 'OPEN' ? 'bi-truck' : 'bi-eye'}`}></i> {c.status === 'OPEN' ? (String(c.id) === String(selectedCycleId) && purchases.length > 0 ? `${purchases.length} Purchase(s)` : 'Manage Purchases →') : 'View Purchases (Read-Only) →'}
                            </button>
                          </td>
                          <td>
                             {c.status === 'OPEN' && (
                              <button className="btn btn-sm btn-dark fw-bold" onClick={() => handleFinalizeCycle(c.id)} disabled={finalizingCycleId === c.id}>
                                {finalizingCycleId === c.id ? (
                                  <><span className="spinner-border spinner-border-sm me-1"></span> Finalizing...</>
                                ) : (
                                  <><i className="bi bi-check-all me-1"></i> Finalize & Issue Bills</>
                                )}
                              </button>
                             )}
                            {c.status === 'FINALIZED' && (
                              <button className="btn btn-sm btn-outline-secondary" onClick={() => handleArchiveCycle(c.id)}>
                                <i className="bi bi-archive me-1"></i> Archive
                              </button>
                            )}
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr><td colSpan="5" className="text-center py-4 text-muted fs-7">No billing cycles opened yet for this building.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Tab 2: Bulk Water Purchases */}
      {activeTab === 'PURCHASES' && (
        <div className="row g-4">
          {cycleSuccessBanner && (
            <div className="col-12">
              <div className="alert alert-success alert-dismissible fade show p-3.5 mb-2 rounded-4 shadow-sm border-start border-success border-4" role="alert">
                <div className="d-flex align-items-center justify-content-between flex-wrap gap-2">
                  <div className="d-flex align-items-center gap-3">
                    <div className="bg-success text-white rounded-circle p-2 d-flex align-items-center justify-content-center" style={{ width: 40, height: 40 }}>
                      <i className="bi bi-check-lg fs-5"></i>
                    </div>
                    <div>
                      <h6 className="fw-bold text-success mb-0.5">Billing Cycle Active &amp; Ready</h6>
                      <p className="text-dark small mb-0">{cycleSuccessBanner}</p>
                    </div>
                  </div>
                  <button type="button" className="btn-close" onClick={() => setCycleSuccessBanner('')}></button>
                </div>
              </div>
            </div>
          )}

          <div className="col-12">
            <div className="alert alert-light border shadow-sm py-2.5 px-3 mb-2 rounded-3 d-flex align-items-center justify-content-between flex-wrap gap-2">
              <div className="d-flex align-items-center gap-2">
                <span className="badge bg-primary px-2.5 py-1.5 fs-8 fw-semibold">Step 2: Bulk Procurement</span>
                <span className="fs-7 text-dark">
                  Target: <strong>Cycle #{selectedCycleId || 'None'}</strong> {currentCycle ? `(${currentCycle.startDate} to ${currentCycle.endDate})` : ''} • Building: <strong>{currentBuilding?.buildingName}</strong>
                </span>
              </div>
              <div className="d-flex gap-2">
                <button className="btn btn-sm btn-outline-secondary fw-semibold" onClick={() => setActiveTab('CYCLES')}>
                  ← 1. Billing Cycles
                </button>
                <button className="btn btn-sm btn-outline-primary fw-semibold" onClick={() => setActiveTab('TARIFF')}>
                  3. Tiered Tariff →
                </button>
              </div>
            </div>
          </div>

          <div className="col-12">
            <div className="row g-3">
              <div className="col-md-4">
                <div className="card glass-card border p-3 shadow-sm bg-white">
                  <small className="text-muted fw-semibold text-uppercase fs-8">Total Purchased Water</small>
                  <h3 className="fw-bold mb-0 mt-1 text-dark">{summary.totalQuantity.toFixed(2)} kL</h3>
                </div>
              </div>
              <div className="col-md-4">
                <div className="card glass-card border p-3 shadow-sm bg-white">
                  <small className="text-muted fw-semibold text-uppercase fs-8">Total Purchase Cost</small>
                  <h3 className="fw-bold mb-0 mt-1 text-dark">₹{summary.totalCost.toFixed(2)}</h3>
                </div>
              </div>
              <div className="col-md-4">
                <div className="card glass-card border p-3 shadow-sm bg-white">
                  <small className="text-muted fw-semibold text-uppercase fs-8">Average Procurement Cost</small>
                  <h3 className="fw-bold mb-0 mt-1 text-dark">₹{averageProcurement.toFixed(2)} / kL</h3>
                </div>
              </div>
            </div>
          </div>

          <div className="col-lg-4">
            <div className="card glass-card border-0 p-4 shadow-sm">
              <h5 className="fw-bold mb-3 text-dark"><i className={`bi ${isFinalized ? "bi-lock-fill" : "bi-truck-front-fill"} text-secondary me-2`}></i>{isFinalized ? "Add Bulk Purchase (Locked / Read-Only)" : "Add Bulk Purchase"}</h5>
              <form onSubmit={handleRecordPurchase}>
                <div className="mb-3">
                  <label className="form-label fw-semibold fs-7 text-dark">Supplier *</label>
                  <select className="form-select" value={purchaseForm.supplier} onChange={e => setPurchaseForm({...purchaseForm, supplier: e.target.value})} disabled={isFinalized} required>
                    <option value="Municipal Water Corporation">Municipal Water Corporation</option>
                    <option value="Water Tanker Vendor">Water Tanker Vendor</option>
                    <option value="Borewell Maintenance">Borewell Maintenance</option>
                  </select>
                </div>
                <div className="mb-3">
                  <label className="form-label fw-semibold fs-7 text-dark">Quantity Purchased (kL) *</label>
                  <input type="number" step="0.1" min="0.1" className="form-control" value={purchaseForm.volume} onChange={e => setPurchaseForm({...purchaseForm, volume: e.target.value})} disabled={isFinalized} required />
                </div>
                <div className="mb-3">
                  <label className="form-label fw-semibold fs-7 text-dark">Unit Cost (₹/kL) *</label>
                  <input type="number" step="0.1" min="0" className="form-control" value={purchaseForm.unitCost} onChange={e => setPurchaseForm({...purchaseForm, unitCost: e.target.value})} disabled={isFinalized} required />
                </div>
                <button type="submit" className={`btn ${isFinalized ? 'btn-secondary' : 'btn-primary'} w-100 fw-bold`} disabled={isFinalized}>
                  <i className={`bi ${isFinalized ? 'bi-lock-fill' : 'bi-plus-circle'} me-1`}></i> {isFinalized ? 'Cycle Finalized (Read-Only)' : 'Record Purchase'}
                </button>
              </form>
            </div>
          </div>

          <div className="col-lg-8">
            <div className="card glass-card border-0 p-4 shadow-sm">
              <h5 className="fw-bold mb-3 text-dark">Bulk Water Purchases History</h5>
              <div className="table-responsive">
                <table className="table custom-table align-middle mb-0">
                  <thead>
                    <tr>
                      <th>Supplier</th>
                      <th>Quantity</th>
                      <th>Unit Cost</th>
                      <th>Total Cost</th>
                      <th>Purchase Date</th>
                      <th>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {purchases.length ? (
                      purchases.map(p => (
                        <tr key={p.id}>
                          <td className="fw-bold text-dark">{p.supplier}</td>
                          <td>{(parseFloat(p.volume || p.quantity || 0)).toFixed(2)} kL</td>
                          <td>₹{parseFloat(p.unitCost || 0).toFixed(2)}</td>
                          <td className="fw-bold text-dark">₹{(parseFloat(p.totalCost || ((p.volume || p.quantity) * p.unitCost))).toFixed(2)}</td>
                          <td className="text-muted small">{p.purchaseDate}</td>
                          <td>
                            <button className="btn btn-sm btn-outline-danger" onClick={() => handleDeletePurchase(p.id)} disabled={isFinalized}>
                              <i className="bi bi-trash"></i>
                            </button>
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr><td colSpan="6" className="text-center py-4 text-muted fs-7">No bulk water purchases recorded yet for this building cycle.</td></tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Tab 3: Tiered Tariff Table */}
      {activeTab === 'TARIFF' && (
        <div className="row g-4">
          <div className="col-12">
            <div className="row g-3 mb-4">
              <div className="col-md-4">
                <div className="card glass-card border p-3 shadow-sm bg-white">
                  <small className="text-muted fw-semibold text-uppercase fs-8">Average Procurement Cost</small>
                  <h3 className="fw-bold mb-0 mt-1 text-dark">₹{averageProcurement.toFixed(2)} / kL</h3>
                  <small className="text-muted fs-8">Total Purchase Cost / Total Volume</small>
                </div>
              </div>
              <div className="col-md-4">
                <div className="card glass-card border p-3 shadow-sm bg-white">
                  <small className="text-muted fw-semibold text-uppercase fs-8">Markup / Operational %</small>
                  <h3 className="fw-bold mb-0 mt-1 text-dark">{markupPct.toFixed(2)}%</h3>
                  <small className="text-muted fs-8">Operational Cost Margin</small>
                </div>
              </div>
              <div className="col-md-4">
                <div className="card glass-card border p-3 shadow-sm bg-white">
                  <small className="text-muted fw-semibold text-uppercase fs-8">Base Resident Tariff</small>
                  <h3 className="fw-bold mb-0 mt-1 text-dark">₹{baseTariffVal.toFixed(2)} / kL</h3>
                  <small className="text-muted fs-8">Average Cost × (1 + Markup %)</small>
                </div>
              </div>
            </div>

            <div className="card glass-card border-0 p-4 shadow-sm mb-4">
              <div className="d-flex align-items-center justify-content-between mb-4 flex-wrap gap-2">
                <div>
                  <h4 className="fw-bold mb-1 text-dark">
                    <i className="bi bi-table text-secondary me-2"></i>Dynamic 3-Tier Tariff Rates Schedule
                  </h4>
                  <p className="text-muted fs-7 mb-0">
                    Dynamically derived from bulk procurement cost for Cycle #{selectedCycleId}
                  </p>
                </div>
                
                {/* Markup Configuration Form */}
                <form onSubmit={handleUpdateMarkup} className="d-flex align-items-center gap-2">
                  <label className="fw-semibold fs-7 text-dark text-nowrap">Markup %:</label>
                  <input
                    type="number"
                    step="0.1"
                    min="0"
                    max="1000"
                    className="form-control form-control-sm text-center fw-bold"
                    style={{ width: '90px' }}
                    value={markupInput}
                    onChange={e => setMarkupInput(e.target.value)}
                    disabled={isFinalized}
                    required
                  />
                  <button type="submit" className="btn btn-sm btn-dark fw-bold text-nowrap" disabled={isFinalized}>
                    Update Markup
                  </button>
                </form>
              </div>

              <div className="table-responsive mb-4">
                <table className="table custom-table align-middle mb-0">
                  <thead>
                    <tr>
                      <th>SLAB TIER</th>
                      <th>CONSUMPTION VOLUME RANGE</th>
                      <th>MULTIPLIER</th>
                      <th>RATE PER UNIT (₹/kL)</th>
                      <th>STATUS</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr>
                      <td>
                        <span className="badge bg-light text-dark border px-3 py-2 fw-semibold fs-7">
                          Tier 1 (Base Slab)
                        </span>
                      </td>
                      <td className="fw-bold text-dark">
                        0.00 kL – {parseFloat(tier1Limit).toFixed(2)} kL
                      </td>
                      <td><code className="text-dark fw-bold">×{tier1MultiplierVal.toFixed(2)}</code></td>
                      <td className="fw-bold text-dark fs-6">
                        ₹{tier1Rate.toFixed(2)} / kL
                      </td>
                      <td>
                        <span className="badge bg-success-subtle text-success border border-success-subtle">ACTIVE</span>
                      </td>
                    </tr>
                    <tr>
                      <td>
                        <span className="badge bg-light text-dark border px-3 py-2 fw-semibold fs-7">
                          Tier 2 (Moderate Slab)
                        </span>
                      </td>
                      <td className="fw-bold text-dark">
                        {parseFloat(tier1Limit).toFixed(2)} kL – {parseFloat(tier2Limit).toFixed(2)} kL
                      </td>
                      <td><code className="text-dark fw-bold">×{tier2MultiplierVal.toFixed(2)}</code></td>
                      <td className="fw-bold text-dark fs-6">
                        ₹{tier2Rate.toFixed(2)} / kL
                      </td>
                      <td>
                        <span className="badge bg-success-subtle text-success border border-success-subtle">ACTIVE</span>
                      </td>
                    </tr>
                    <tr>
                      <td>
                        <span className="badge bg-light text-dark border px-3 py-2 fw-semibold fs-7">
                          Tier 3 (High Consumption)
                        </span>
                      </td>
                      <td className="fw-bold text-dark">
                        Above {parseFloat(tier2Limit).toFixed(2)} kL
                      </td>
                      <td><code className="text-dark fw-bold">×{tier3MultiplierVal.toFixed(2)}</code></td>
                      <td className="fw-bold text-dark fs-6">
                        ₹{tier3Rate.toFixed(2)} / kL
                      </td>
                      <td>
                        <span className="badge bg-success-subtle text-success border border-success-subtle">ACTIVE</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>

              {/* Minimalist Formula Cards */}
              <div className="row g-3">
                <div className="col-md-4">
                  <div className="p-3 bg-light rounded-3 border">
                    <small className="text-muted fw-semibold d-block text-uppercase fs-8">Tier 1 Formula</small>
                    <div className="fw-bold text-dark mt-1 fs-7">
                      Base Tariff (₹{baseTariffVal.toFixed(2)}) × 1.00 = ₹{tier1Rate.toFixed(2)}/kL
                    </div>
                  </div>
                </div>
                <div className="col-md-4">
                  <div className="p-3 bg-light rounded-3 border">
                    <small className="text-muted fw-semibold d-block text-uppercase fs-8">Tier 2 Formula</small>
                    <div className="fw-bold text-dark mt-1 fs-7">
                      Base Tariff (₹{baseTariffVal.toFixed(2)}) × 1.25 = ₹{tier2Rate.toFixed(2)}/kL
                    </div>
                  </div>
                </div>
                <div className="col-md-4">
                  <div className="p-3 bg-light rounded-3 border">
                    <small className="text-muted fw-semibold d-block text-uppercase fs-8">Tier 3 Formula</small>
                    <div className="fw-bold text-dark mt-1 fs-7">
                      Base Tariff (₹{baseTariffVal.toFixed(2)}) × 1.50 = ₹{tier3Rate.toFixed(2)}/kL
                    </div>
                  </div>
                </div>
              </div>

            </div>
          </div>
        </div>
      )}

      {/* Tab 4: Invoices */}
      {activeTab === 'INVOICES' && (
        <div className="card glass-card border-0 shadow-sm">
          <div className="card-body p-0">
            <div className="table-responsive">
              <table className="table custom-table mb-0 align-middle">
                <thead>
                  <tr>
                    <th>Bill ID</th>
                    <th>Resident</th>
                    <th>Building / Apt</th>
                    <th>Period</th>
                    <th>Units</th>
                    <th>3-Tier Breakdown</th>
                    <th>Amount</th>
                    <th>Due Date</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {bills.length ? (
                    bills.map(b => (
                      <tr key={b.billId}>
                        <td><code className="text-dark fw-bold">#SWB-{b.billId}</code></td>
                        <td className="fw-bold text-dark">{b.residentName}</td>
                        <td>{b.buildingName} ({b.apartmentNumber})</td>
                        <td>{b.billMonth}/{b.billYear}</td>
                        <td><span className="badge bg-secondary-subtle text-dark border">{b.units} kL</span></td>
                        <td>
                          <small className="d-block text-muted fs-8">
                            T1: {b.tier1Units || 0}u (₹{b.tier1Amount || 0}) | T2: {b.tier2Units || 0}u (₹{b.tier2Amount || 0}) | T3: {b.tier3Units || 0}u (₹{b.tier3Amount || 0})
                          </small>
                        </td>
                        <td className="fw-extrabold text-dark fs-6">₹{b.amount?.toFixed(2)}</td>
                        <td className="text-muted small">{b.dueDate}</td>
                        <td>
                          <span className={`badge badge-custom ${b.status === 'PAID' ? 'badge-paid' : 'badge-unpaid'}`}>
                            {b.status}
                          </span>
                        </td>
                      </tr>
                    ))
                  ) : (
                    <tr><td colSpan="9" className="text-center py-4 text-muted">No monthly bills generated yet.</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
