package com.green.greengram.feed.like;

import com.green.greengram.entity.FeedLike;
import com.green.greengram.entity.FeedLikeIds;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedLikeRepository extends JpaRepository<FeedLike, FeedLikeIds> {
}
