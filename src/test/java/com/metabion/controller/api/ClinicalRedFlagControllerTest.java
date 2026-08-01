package com.metabion.controller.api;

import com.metabion.domain.RedFlagSeverity;
import com.metabion.domain.RedFlagSourceType;
import com.metabion.domain.RoleName;
import com.metabion.dto.redflag.ClinicalRedFlagEventResponse;
import com.metabion.dto.redflag.ClinicalRedFlagHistoryResponse;
import com.metabion.dto.redflag.ClinicalRedFlagSnapshotResponse;
import com.metabion.dto.redflag.RedFlagHistoryQuery;
import com.metabion.dto.redflag.RedFlagMatchedInputsResponse;
import com.metabion.service.SecurityService;
import com.metabion.service.UserService;
import com.metabion.service.redflag.RedFlagEventQueryService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.profiles.active=dev",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:h2:mem:clinical_red_flag_controller_test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.autoconfigure.exclude=org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionAutoConfiguration"
})
class ClinicalRedFlagControllerTest {

    @Autowired
    WebApplicationContext context;

    @MockitoBean
    FindByIndexNameSessionRepository<Session> sessions;

    @MockitoBean
    UserService userService;

    @MockitoBean
    SecurityService securityService;

    @MockitoBean
    RedFlagEventQueryService redFlags;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Filter[] filters = context.getBeansOfType(Filter.class).values().toArray(new Filter[0]);
        mvc = MockMvcBuilders
                .webAppContextSetup(context)
                .addFilters(filters)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void clinicalCurrentRouteReturnsNoStoreSnapshot() throws Exception {
        when(redFlags.currentForClinicalPatient(any(), eq(41L))).thenReturn(snapshotResponse());

        mvc.perform(get("/api/clinical/patients/41/red-flags/current")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.highestSeverity").value("EMERGENCY"))
                .andExpect(jsonPath("$.flags[0].ruleVersion").value(1))
                .andExpect(jsonPath("$.flags[0].matchedInputs.facts[0].factKey").value("lab.CRP"));

        verify(redFlags).currentForClinicalPatient(any(), eq(41L));
    }

    @Test
    void clinicalHistoryRouteForwardsFiltersAndOmitsWriteOnlyFields() throws Exception {
        when(redFlags.historyForClinicalPatient(any(), eq(41L), any())).thenReturn(historyResponse());

        mvc.perform(get("/api/clinical/patients/41/red-flags/history")
                        .with(user("doctor@example.com").roles(RoleName.PHYSICIAN.name()))
                        .param("severity", "EMERGENCY")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.items[0].ruleVersion").value(1))
                .andExpect(jsonPath("$.items[0].matchedInputs.facts[0].factKey").value("lab.CRP"))
                .andExpect(jsonPath("$.items[0].evaluationRunId").doesNotExist())
                .andExpect(jsonPath("$.items[0].sourceOperation").doesNotExist())
                .andExpect(jsonPath("$.items[0].matchedGroupKey").doesNotExist());

        verify(redFlags).historyForClinicalPatient(
                any(),
                eq(41L),
                eq(new RedFlagHistoryQuery(null, null, RedFlagSeverity.EMERGENCY, null, 20)));
    }

    @Test
    void unauthenticatedClinicalRoutesReturnUnauthorized() throws Exception {
        mvc.perform(get("/api/clinical/patients/41/red-flags/history"))
                .andExpect(status().isUnauthorized());
    }

    private ClinicalRedFlagSnapshotResponse snapshotResponse() {
        return new ClinicalRedFlagSnapshotResponse(
                RedFlagSeverity.EMERGENCY,
                List.of(eventResponse()));
    }

    private ClinicalRedFlagHistoryResponse historyResponse() {
        return new ClinicalRedFlagHistoryResponse(List.of(eventResponse()), "next-1");
    }

    private ClinicalRedFlagEventResponse eventResponse() {
        return new ClinicalRedFlagEventResponse(
                81L,
                "LAB_CRP_HIGH",
                RedFlagSeverity.EMERGENCY,
                Instant.parse("2026-07-31T08:15:00Z"),
                RedFlagSourceType.LAB_RESULT_SET,
                90L,
                true,
                null,
                1,
                new RedFlagMatchedInputsResponse(List.of(
                        new RedFlagMatchedInputsResponse.Fact(
                                RedFlagSourceType.LAB_RESULT_SET,
                                90L,
                                "lab.CRP",
                                LocalDate.of(2026, 7, 31),
                                "120.5",
                                null,
                                "mg/L"))));
    }
}
