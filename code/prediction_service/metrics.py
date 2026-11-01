"""Ranking metrics. `groups` is a list of (relevance_array, score_array), one per payment."""
import numpy as np


def ndcg_at_k(groups, k=3):
    out = []
    for rel, score in groups:
        rel, score = np.asarray(rel, dtype=float), np.asarray(score, dtype=float)
        order = np.argsort(-score)[:k]
        dcg = sum((2 ** rel[i] - 1) / np.log2(r + 2) for r, i in enumerate(order))
        ideal = sorted(rel, reverse=True)[:k]
        idcg = sum((2 ** g - 1) / np.log2(r + 2) for r, g in enumerate(ideal))
        out.append(dcg / idcg if idcg > 0 else 1.0)
    return float(np.mean(out))


def top1(groups):
    return float(np.mean([np.asarray(rel)[np.argmax(score)] == np.max(rel) for rel, score in groups]))
