// BMPS Trading Platform - Modern UI
// Clean, professional interface for trading visualization

class BMPSApp {
  constructor() {
    this.currentTab = 'config';
    this.isPlaying = false;
    this.currentTime = '00:00:00';
    this.playbackSpeed = 1.0;
    
    this.init();
  }

  init() {
    this.setupTabNavigation();
    this.setupConfigForm();
    this.setupChartControls();
    this.setTodayAsDefault();
  }

  setupTabNavigation() {
    const tabButtons = document.querySelectorAll('.tab-btn');
    const pages = document.querySelectorAll('.page');

    tabButtons.forEach(button => {
      button.addEventListener('click', () => {
        const targetTab = button.dataset.tab;
        this.switchTab(targetTab);
      });
    });
  }

  switchTab(tabName) {
    // Update tab buttons
    document.querySelectorAll('.tab-btn').forEach(btn => {
      btn.classList.remove('active');
    });
    document.querySelector(`[data-tab="${tabName}"]`).classList.add('active');

    // Update pages
    document.querySelectorAll('.page').forEach(page => {
      page.classList.remove('active');
    });
    document.getElementById(`${tabName}-page`).classList.add('active');

    this.currentTab = tabName;
  }

  setupConfigForm() {
    const form = document.querySelector('.config-form');
    const startBtn = document.querySelector('.start-btn');

    form.addEventListener('submit', (e) => {
      e.preventDefault();
      this.startPlanning();
    });
  }

  startPlanning() {
    const tradingDate = document.getElementById('trading-date').value;
    const planningDays = document.getElementById('planning-days').value;

    if (!tradingDate) {
      alert('Please select a trading date.');
      return;
    }

    // For now, just switch to the planning chart
    // In the future, this will also start the backend planning process
    this.switchTab('planning');
    
    console.log(`Starting planning for ${tradingDate} with ${planningDays} days`);
  }

  setupChartControls() {
    // Rewind button
    document.querySelectorAll('.chart-controls .control-btn').forEach((btn, index) => {
      btn.addEventListener('click', () => {
        switch(index % 5) { // 5 buttons per chart control set
          case 0: this.rewind(); break;
          case 1: this.stepBackward(); break;
          case 2: this.togglePlayPause(btn); break;
          case 3: this.stepForward(); break;
          case 4: this.fastForward(); break;
        }
      });
    });
  }

  rewind() {
    console.log('Rewind to start');
    this.currentTime = '00:00:00';
    this.updateTimeDisplay();
    // Future: Reset chart to first candle
  }

  stepBackward() {
    console.log('Step backward');
    // Future: Go back one candle
  }

  togglePlayPause(button) {
    this.isPlaying = !this.isPlaying;
    
    // Update button appearance
    document.querySelectorAll('.play-pause').forEach(btn => {
      btn.textContent = this.isPlaying ? '⏸' : '▶';
      btn.title = this.isPlaying ? 'Pause' : 'Play';
    });

    console.log(this.isPlaying ? 'Playing' : 'Paused');
    // Future: Start/stop candle playback
  }

  stepForward() {
    console.log('Step forward');
    // Future: Go forward one candle
  }

  fastForward() {
    console.log('Fast forward to end');
    // Future: Jump to last candle
  }

  updateTimeDisplay() {
    document.querySelectorAll('.time-display').forEach(display => {
      display.textContent = this.currentTime;
    });
  }

  setTodayAsDefault() {
    const today = new Date();
    const dateStr = today.toISOString().split('T')[0];
    document.getElementById('trading-date').value = dateStr;
  }

  // Future methods for chart integration
  initializePlanningChart() {
    // Will integrate with LightweightCharts
  }

  initializeTradingChart() {
    // Will integrate with LightweightCharts
  }

  connectWebSocket() {
    // Will connect to backend WebSocket
  }
}

// Initialize the app when DOM is loaded
document.addEventListener('DOMContentLoaded', () => {
  window.bmpsApp = new BMPSApp();
  console.log('BMPS Trading Platform initialized');
});

// For debugging
window.switchTab = (tabName) => {
  if (window.bmpsApp) {
    window.bmpsApp.switchTab(tabName);
  }
};