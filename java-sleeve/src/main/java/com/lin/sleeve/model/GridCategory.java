package com.lin.sleeve.model;

import org.hibernate.annotations.Where;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * @author Ztiany
 * Email ztiany3@gmail.com
 * Date 2021/1/25 16:28
 */
@Entity
@Getter
@Setter
@ToString
//对所有查询都加上一个条件，应用场景：逻辑删除的数据不应该被查询出来。
@Where(clause = "delete_time is null")
public class GridCategory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String img;
    private String name;
    private Integer categoryId;
    private Integer rootCategoryId;

}
