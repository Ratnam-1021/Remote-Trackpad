/**
 * BlanketCozzy - Trackpad
 * Real-time Synchronized Trackpad & Cursor Demo Engine
 */

(function () {
  'use strict';

  // Percentage bounds inside the 1440x490 panoramic stage:
  // Phone trackpad area
  const PHONE_BOUNDS = {
    left: 66.32,
    top: 46.53,
    width: 5.625,
    height: 24.90
  };

  // Laptop screen corners (perspective mapped in % of hero stage)
  // Top-Left, Top-Right, Bottom-Left, Bottom-Right
  const LAPTOP_CORNERS = {
    TL: { x: 76.81, y: 24.49 },
    TR: { x: 97.08, y: 17.55 },
    BL: { x: 78.61, y: 71.02 },
    BR: { x: 98.33, y: 62.86 }
  };

  const phoneTrackpad = document.getElementById('phoneTrackpad');
  const touchDot = document.getElementById('touchDot');
  const touchRipple = document.getElementById('touchRipple');
  const laptopCursor = document.getElementById('laptopCursor');
  const laptopRipple = document.getElementById('laptopRipple');

  if (!phoneTrackpad || !touchDot || !laptopCursor) {
    return;
  }

  let isUserInteracting = false;
  let userIdleTimeout = null;
  let animationFrameId = null;

  // Normalized coordinates (0.0 to 1.0)
  let currentU = 0.52;
  let currentV = 0.48;

  /**
   * Set positions on phone trackpad and laptop screen in lockstep
   */
  function updateSyncPositions(u, v, triggerClick = false) {
    // Clamp coordinates safely within trackpad margins
    u = Math.max(0.06, Math.min(0.94, u));
    v = Math.max(0.06, Math.min(0.94, v));

    currentU = u;
    currentV = v;

    // 1. Phone Touch Dot Position (%)
    const px = PHONE_BOUNDS.left + u * PHONE_BOUNDS.width;
    const py = PHONE_BOUNDS.top + v * PHONE_BOUNDS.height;

    touchDot.style.left = `${px}%`;
    touchDot.style.top = `${py}%`;

    // 2. Laptop Cursor Position (%) via Bilinear Interpolation
    const { TL, TR, BL, BR } = LAPTOP_CORNERS;
    const lx = (1 - u) * (1 - v) * TL.x + u * (1 - v) * TR.x + (1 - u) * v * BL.x + u * v * BR.x;
    const ly = (1 - u) * (1 - v) * TL.y + u * (1 - v) * TR.y + (1 - u) * v * BL.y + u * v * BR.y;

    laptopCursor.style.left = `${lx}%`;
    laptopCursor.style.top = `${ly}%`;

    if (triggerClick) {
      playClickRipple(px, py, lx, ly);
    }
  }

  function playClickRipple(px, py, lx, ly) {
    if (touchRipple) {
      touchRipple.style.left = `${px}%`;
      touchRipple.style.top = `${py}%`;
      touchRipple.classList.remove('active');
      void touchRipple.offsetWidth; // re-flow
      touchRipple.classList.add('active');
    }

    if (laptopRipple) {
      laptopRipple.style.left = `${lx}%`;
      laptopRipple.style.top = `${ly}%`;
      laptopRipple.classList.remove('active');
      void laptopRipple.offsetWidth;
      laptopRipple.classList.add('active');
    }
  }

  // -------------------------------------------------------------
  // Natural Autonomous Movement Loop (Runs when user is not active)
  // -------------------------------------------------------------
  let autoTime = 0;
  let lastClickTime = 0;

  function runAutonomousLoop() {
    if (!isUserInteracting) {
      autoTime += 0.014; // ~60fps smooth progression

      // Smooth human-like wandering path inside the trackpad
      const u = 0.50 + 0.28 * Math.sin(autoTime * 0.8) + 0.06 * Math.sin(autoTime * 1.9);
      const v = 0.50 + 0.24 * Math.cos(autoTime * 0.6) + 0.05 * Math.cos(autoTime * 1.5);

      // Trigger realistic click pulses periodically
      const now = performance.now();
      const shouldClick = (now - lastClickTime > 4000) && (Math.sin(autoTime * 1.6) > 0.92);

      if (shouldClick) {
        lastClickTime = now;
        updateSyncPositions(u, v, true);
      } else {
        updateSyncPositions(u, v, false);
      }
    }

    animationFrameId = requestAnimationFrame(runAutonomousLoop);
  }

  // -------------------------------------------------------------
  // Real-time Interactive Touch & Mouse Tracking
  // -------------------------------------------------------------
  function handlePointerMove(e) {
    isUserInteracting = true;
    clearTimeout(userIdleTimeout);

    const rect = phoneTrackpad.getBoundingClientRect();
    const clientX = e.touches ? e.touches[0].clientX : e.clientX;
    const clientY = e.touches ? e.touches[0].clientY : e.clientY;

    const u = (clientX - rect.left) / rect.width;
    const v = (clientY - rect.top) / rect.height;

    updateSyncPositions(u, v);

    // Resume autonomous playback after 2.5s of inactivity
    userIdleTimeout = setTimeout(() => {
      isUserInteracting = false;
    }, 2500);
  }

  function handlePointerDown(e) {
    isUserInteracting = true;
    clearTimeout(userIdleTimeout);

    const rect = phoneTrackpad.getBoundingClientRect();
    const clientX = e.touches ? e.touches[0].clientX : e.clientX;
    const clientY = e.touches ? e.touches[0].clientY : e.clientY;

    const u = (clientX - rect.left) / rect.width;
    const v = (clientY - rect.top) / rect.height;

    updateSyncPositions(u, v, true);
  }

  phoneTrackpad.addEventListener('mousemove', handlePointerMove, { passive: true });
  phoneTrackpad.addEventListener('mousedown', handlePointerDown);
  phoneTrackpad.addEventListener('touchmove', handlePointerMove, { passive: true });
  phoneTrackpad.addEventListener('touchstart', handlePointerDown, { passive: true });

  phoneTrackpad.addEventListener('mouseleave', () => {
    userIdleTimeout = setTimeout(() => {
      isUserInteracting = false;
    }, 1200);
  });

  // Start autonomous loop
  animationFrameId = requestAnimationFrame(runAutonomousLoop);

  // =========================================================================
  // REVOLVING ORBITAL FEATURES ANIMATION ENGINE
  // =========================================================================
  const orbitStage = document.getElementById('featuresOrbitStage');
  const orbitNodesContainer = document.getElementById('orbitNodesContainer');

  const orbitDot = document.getElementById('orbitPhoneDot');
  const slotLeft = document.getElementById('slotLeft');
  const slotPresent = document.getElementById('slotPresent');
  const slotType = document.getElementById('slotType');
  const slotRight = document.getElementById('slotRight');
  const phoneAppHeader = document.getElementById('phoneAppHeader');
  const phoneStatusPill = document.getElementById('phoneStatusPill');

  const dockSlots = [slotLeft, slotPresent, slotType, slotRight];

  function setActiveDockSlot(activeSlot) {
    dockSlots.forEach(s => s && s.classList.remove('active'));
    if (activeSlot) activeSlot.classList.add('active');
  }

  // Exact equidistant 60° (π/3) angular separation between all 6 nodes:
  // Node 1: Native Gestures (240° / 4π/3) - Top-Left
  // Node 2: Presentation Clicker (180° / π) - Mid-Left
  // Node 3: Remote Typing (120° / 2π/3) - Bottom-Left
  // Node 4: Wi-Fi & Bluetooth HID (60° / π/3) - Bottom-Right
  // Node 5: Zero-Config Discovery (0° / 0) - Mid-Right
  // Node 6: Hardware Clicks (300° / 5π/3) - Top-Right
  const orbitNodes = [
    { el: document.getElementById('nodeGestures'), baseAngle: (240 * Math.PI) / 180 },
    { el: document.getElementById('nodeClicker'), baseAngle: (180 * Math.PI) / 180 },
    { el: document.getElementById('nodeTyping'), baseAngle: (120 * Math.PI) / 180 },
    { el: document.getElementById('nodeWireless'), baseAngle: (60 * Math.PI) / 180 },
    { el: document.getElementById('nodeDiscovery'), baseAngle: (0 * Math.PI) / 180 },
    { el: document.getElementById('nodeHardware'), baseAngle: (300 * Math.PI) / 180 },
  ];

  let orbitAngleOffset = 0;
  let isOrbitHoverPaused = false;
  let orbitAnimFrame = null;

  // Orbit Ellipse Radii matching SVG precisely (with generous clearance around phone)
  const ORBIT_RX = 250;
  const ORBIT_RY = 280;

  function renderOrbitNodes() {
    if (!orbitStage || !orbitNodesContainer || window.innerWidth <= 992) return;

    const stageRect = orbitStage.getBoundingClientRect();
    const centerX = stageRect.width / 2;
    const centerY = stageRect.height / 2;

    orbitNodes.forEach(({ el, baseAngle }) => {
      if (!el) return;

      const currentAngle = baseAngle + orbitAngleOffset;
      const cosA = Math.cos(currentAngle);
      const sinA = Math.sin(currentAngle);

      const x = centerX + ORBIT_RX * cosA;
      const y = centerY + ORBIT_RY * sinA;

      el.style.left = `${x.toFixed(1)}px`;
      el.style.top = `${y.toFixed(1)}px`;

      // Dynamically orient layout: outward from central phone
      if (cosA >= 0) {
        if (!el.classList.contains('is-right')) {
          el.classList.add('is-right');
          el.classList.remove('is-left');
        }
      } else {
        if (!el.classList.contains('is-left')) {
          el.classList.add('is-left');
          el.classList.remove('is-right');
        }
      }
    });
  }

  function loopOrbit() {
    if (!isOrbitHoverPaused && window.innerWidth > 992) {
      // Slow, luxurious revolution: 1 full cycle every ~45 seconds
      orbitAngleOffset += 0.0022;
      if (orbitAngleOffset >= Math.PI * 2) {
        orbitAngleOffset -= Math.PI * 2;
      }
      renderOrbitNodes();
    }
    orbitAnimFrame = requestAnimationFrame(loopOrbit);
  }

  if (orbitStage && orbitNodes[0].el) {
    renderOrbitNodes();
    orbitAnimFrame = requestAnimationFrame(loopOrbit);
    window.addEventListener('resize', renderOrbitNodes, { passive: true });

    // Hover on stage pauses revolution
    orbitStage.addEventListener('mouseenter', () => {
      isOrbitHoverPaused = true;
    });
    orbitStage.addEventListener('mouseleave', () => {
      isOrbitHoverPaused = false;
    });
  }

  // Node Hover Interactivity with Central Phone Device UI
  const allNodes = document.querySelectorAll('.orbit-node');
  allNodes.forEach(node => {
    const feature = node.getAttribute('data-feature');

    node.addEventListener('mouseenter', () => {
      if (feature === 'gestures' && orbitDot) {
        orbitDot.style.transition = 'transform 0.35s cubic-bezier(0.16, 1, 0.3, 1)';
        orbitDot.style.transform = 'translate(-35%, -65%) scale(1.15)';
      } else if (feature === 'clicker') {
        setActiveDockSlot(slotPresent);
      } else if (feature === 'typing') {
        setActiveDockSlot(slotType);
      } else if (feature === 'hardware') {
        setActiveDockSlot(slotRight);
      } else if (feature === 'discovery' && phoneAppHeader) {
        phoneAppHeader.style.borderColor = 'rgba(56, 189, 248, 0.6)';
        phoneAppHeader.style.background = 'rgba(56, 189, 248, 0.12)';
      } else if (feature === 'wireless' && phoneStatusPill) {
        phoneStatusPill.style.boxShadow = '0 0 12px rgba(34, 197, 94, 0.7)';
      }
    });

    node.addEventListener('mouseleave', () => {
      setActiveDockSlot(slotLeft);
      if (orbitDot) {
        orbitDot.style.transform = 'translate(-50%, -50%) scale(1)';
      }
      if (phoneAppHeader) {
        phoneAppHeader.style.borderColor = 'rgba(255, 255, 255, 0.08)';
        phoneAppHeader.style.background = 'rgba(255, 255, 255, 0.05)';
      }
      if (phoneStatusPill) {
        phoneStatusPill.style.boxShadow = 'none';
      }
    });
  });

  // -------------------------------------------------------------
  // Centered Smooth Scrolling for Navbar Links
  // When a link is clicked, the target section scrolls smoothly into
  // the exact vertical center of the viewport for maximum focus.
  // -------------------------------------------------------------
  function scrollTargetIntoCenter(target) {
    if (!target) return;

    const nav = document.querySelector('.navbar');
    const navHeight = nav ? nav.offsetHeight : 68;
    const targetRect = target.getBoundingClientRect();
    const targetHeight = targetRect.height;
    const targetAbsoluteTop = window.pageYOffset + targetRect.top;
    const visibleViewportHeight = window.innerHeight - navHeight;

    let scrollToY;
    if (targetHeight <= visibleViewportHeight) {
      // Center the section vertically within the visible area below the navbar
      const remainingSpace = visibleViewportHeight - targetHeight;
      scrollToY = targetAbsoluteTop - navHeight - (remainingSpace / 2);
    } else {
      // If the section is taller than visible height, position its top comfortably below the navbar
      scrollToY = targetAbsoluteTop - navHeight - 16;
    }

    const maxScroll = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
    const finalY = Math.max(0, Math.min(maxScroll, scrollToY));

    window.scrollTo({
      top: finalY,
      behavior: 'smooth'
    });
  }

  // Bind click handlers to all anchor links targeting page sections
  document.querySelectorAll('a[href^="#"]').forEach(link => {
    link.addEventListener('click', function (e) {
      const hash = this.getAttribute('href');
      if (!hash || hash === '#' || hash.length <= 1) return;

      const target = document.querySelector(hash);
      if (target) {
        e.preventDefault();
        scrollTargetIntoCenter(target);
        if (history.pushState) {
          history.pushState(null, null, hash);
        }
      }
    });
  });

})();
