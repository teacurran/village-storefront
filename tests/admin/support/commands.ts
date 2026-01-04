/**
 * Custom Cypress commands for Village Storefront tests
 */

// Declare custom command types
declare global {
  namespace Cypress {
    interface Chainable {
      /**
       * Login as POS user
       */
      loginPOS(deviceId: string, passcode: string): Chainable<void>;

      /**
       * Simulate offline mode
       */
      goOffline(): Chainable<void>;

      /**
       * Simulate online mode
       */
      goOnline(): Chainable<void>;

      /**
       * Seed POS offline queue
       */
      seedOfflineQueue(transactions: any[]): Chainable<void>;

      /**
       * Get IndexedDB data for POS offline storage
       */
      getIndexedDB(storeName: string): Chainable<any[]>;
    }
  }
}

// Login as POS user
Cypress.Commands.add('loginPOS', (deviceId: string, passcode: string) => {
  cy.visit('/admin/pos/login');
  cy.get('[data-test="device-id"]').type(deviceId);
  cy.get('[data-test="passcode"]').type(passcode);
  cy.get('[data-test="login-button"]').click();
  cy.url().should('include', '/admin/pos');
});

// Simulate offline mode
Cypress.Commands.add('goOffline', () => {
  cy.log('Simulating offline mode');
  cy.window().then((win) => {
    // Override navigator.onLine
    Object.defineProperty(win.navigator, 'onLine', {
      writable: true,
      value: false,
    });
    win.dispatchEvent(new Event('offline'));
  });
});

// Simulate online mode
Cypress.Commands.add('goOnline', () => {
  cy.log('Simulating online mode');
  cy.window().then((win) => {
    Object.defineProperty(win.navigator, 'onLine', {
      writable: true,
      value: true,
    });
    win.dispatchEvent(new Event('online'));
  });
});

// Seed offline queue
Cypress.Commands.add('seedOfflineQueue', (transactions: any[]) => {
  cy.window().then((win) => {
    const dbRequest = win.indexedDB.open('village-pos-offline', 1);

    dbRequest.onsuccess = () => {
      const db = dbRequest.result;
      const transaction = db.transaction(['pending_transactions'], 'readwrite');
      const store = transaction.objectStore('pending_transactions');

      transactions.forEach((txn) => {
        store.add(txn);
      });

      transaction.oncomplete = () => {
        db.close();
      };
    };
  });
});

// Get IndexedDB data
Cypress.Commands.add('getIndexedDB', (storeName: string) => {
  return cy.window().then(
    (win) =>
      new Cypress.Promise((resolve, reject) => {
        const dbRequest = win.indexedDB.open('village-pos-offline', 1);

        dbRequest.onsuccess = () => {
          const db = dbRequest.result;
          const transaction = db.transaction([storeName], 'readonly');
          const store = transaction.objectStore(storeName);
          const getAllRequest = store.getAll();

          getAllRequest.onsuccess = () => {
            resolve(getAllRequest.result);
            db.close();
          };

          getAllRequest.onerror = () => {
            reject(getAllRequest.error);
            db.close();
          };
        };

        dbRequest.onerror = () => {
          reject(dbRequest.error);
        };
      })
  );
});

export {};
