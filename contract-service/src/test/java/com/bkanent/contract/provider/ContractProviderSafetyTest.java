package com.bkanent.contract.provider;

import com.bkanent.contract.client.EsignCnContractEsignClient;
import com.bkanent.contract.client.FadadaContractEsignClient;
import com.bkanent.contract.client.MockContractEsignClient;
import com.bkanent.contract.config.ContractProviderNames;
import com.bkanent.contract.ocr.MockContractOcrExtractor;
import com.bkanent.contract.ocr.VendorContractOcrExtractor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractProviderSafetyTest {

    @Test
    void mockProvidersRequireExplicitProviderName() {
        assertTrue(new MockContractOcrExtractor().supports(ContractProviderNames.OCR_MOCK));
        assertFalse(new MockContractOcrExtractor().supports(null));
        assertTrue(new MockContractEsignClient().supports(ContractProviderNames.ESIGN_MOCK));
        assertFalse(new MockContractEsignClient().supports(null));
    }

    @Test
    void placeholderRealProvidersNeverReportSuccess() {
        assertThrows(UnsupportedOperationException.class,
                () -> new VendorContractOcrExtractor().extract("ID_CARD", "id.pdf", "https://example.test/id.pdf"));
        assertThrows(UnsupportedOperationException.class,
                () -> new EsignCnContractEsignClient().sealContract(null, null));
        assertThrows(UnsupportedOperationException.class,
                () -> new FadadaContractEsignClient().sealContract(null, null));
    }
}
