package com.project.iot_spring.web;

import com.project.iot_spring.web.PageResponse.Paging;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageResponseTest {

    @Test
    void defaultsAndLimits() {
        assertThat(Paging.of(null, null)).isEqualTo(new Paging(0, 24));
        assertThat(Paging.of(2, 500)).isEqualTo(new Paging(2, 100));
        assertThat(Paging.of(3, 10).offset()).isEqualTo(30);
        assertThatThrownBy(() -> Paging.of(-1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Paging.of(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totalPages() {
        assertThat(PageResponse.of(List.of(), new Paging(0, 24), 0).totalPages()).isZero();
        assertThat(PageResponse.of(List.of(), new Paging(0, 24), 24).totalPages()).isEqualTo(1);
        assertThat(PageResponse.of(List.of(), new Paging(5, 24), 131).totalPages()).isEqualTo(6);
        PageResponse<Integer> page = PageResponse.of(List.of(1, 2), new Paging(1, 2), 5);
        assertThat(page.map(i -> i * 10).content()).containsExactly(10, 20);
        assertThat(page.map(i -> i * 10).totalPages()).isEqualTo(3);
    }
}
