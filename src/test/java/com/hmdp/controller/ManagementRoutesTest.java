package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class ManagementRoutesTest {
    private MockMvc mvc;
    private IShopService shopService;
    private IVoucherService voucherService;

    @BeforeEach
    void setUp() {
        shopService = mock(IShopService.class);
        voucherService = mock(IVoucherService.class);
        ShopController shopController = new ShopController();
        VoucherController voucherController = new VoucherController();
        ReflectionTestUtils.setField(shopController, "shopService", shopService);
        ReflectionTestUtils.setField(voucherController, "voucherService", voucherService);
        mvc = MockMvcBuilders.standaloneSetup(shopController, voucherController, new UploadController()).build();
    }

    @Test
    void managementWritesAndImageDeletionAreUnavailable() throws Exception {
        mvc.perform(post("/shop")).andExpect(status().is4xxClientError());
        mvc.perform(put("/shop")).andExpect(status().is4xxClientError());
        mvc.perform(post("/voucher")).andExpect(status().is4xxClientError());
        mvc.perform(post("/voucher/seckill")).andExpect(status().is4xxClientError());
        mvc.perform(get("/upload/blog/delete").param("name", "../outside"))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(shopService, voucherService);
    }

    @Test
    void publicReadsRemainAvailable() throws Exception {
        when(shopService.quaryById(1L)).thenReturn(Result.ok(new Shop()));
        when(voucherService.queryVoucherOfShop(1L)).thenReturn(Result.ok(Collections.emptyList()));
        mvc.perform(get("/shop/1")).andExpect(status().isOk());
        mvc.perform(get("/voucher/list/1")).andExpect(status().isOk());
    }

    @Test
    void uploadsRejectExecutableFilesAndDisguisedImages() throws Exception {
        mvc.perform(multipart("/upload/blog").file(new MockMultipartFile(
                        "file", "page.html", "text/html", "<script>alert(1)</script>".getBytes())))
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(multipart("/upload/blog").file(new MockMultipartFile(
                        "file", "page.jpg", "image/jpeg", "<script>alert(1)</script>".getBytes())))
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(multipart("/upload/blog").file(new MockMultipartFile(
                        "file", "large.jpg", "image/jpeg", new byte[1024 * 1024 + 1])))
                .andExpect(jsonPath("$.success").value(false));
    }
}
