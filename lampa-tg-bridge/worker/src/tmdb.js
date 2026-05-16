const TMDB_BASE = 'https://api.themoviedb.org/3';

export async function searchMovie(query, apiKey) {
    const url = `${TMDB_BASE}/search/multi?api_key=${apiKey}&language=ru-RU&query=${encodeURIComponent(query)}&include_adult=false`;
    const res = await fetch(url);
    if (!res.ok) return null;
    const data = await res.json();
    if (!data.results || data.results.length === 0) return null;
    const items = data.results.filter(r => r.media_type === 'movie' || r.media_type === 'tv');
    return items[0] || null;
}

export async function findByImdbId(imdbId, apiKey) {
    const url = `${TMDB_BASE}/find/${imdbId}?api_key=${apiKey}&external_source=imdb_id&language=ru-RU`;
    const res = await fetch(url);
    if (!res.ok) return null;
    const data = await res.json();
    const results = [...(data.movie_results || []), ...(data.tv_results || [])];
    return results[0] || null;
}

export async function getMovieByTmdbId(tmdbId, apiKey) {
    const res = await fetch(`${TMDB_BASE}/movie/${tmdbId}?api_key=${apiKey}&language=ru-RU`);
    if (!res.ok) return null;
    const data = await res.json();
    return { ...data, media_type: 'movie' };
}

export function normalizeResult(item) {
    if (!item) return null;
    const isMovie = item.media_type === 'movie' || (!item.media_type && item.title);
    return {
        id: item.id,
        title: isMovie ? (item.title || item.name) : (item.name || item.title),
        year: isMovie
            ? (item.release_date || '').slice(0, 4)
            : (item.first_air_date || '').slice(0, 4),
        poster: item.poster_path ? `https://image.tmdb.org/t/p/w500${item.poster_path}` : null,
        vote: item.vote_average ? Number(item.vote_average).toFixed(1) : null,
        media_type: isMovie ? 'movie' : 'tv'
    };
}

export function parseLinkFromText(text) {
    const tmdbMatch = text.match(/themoviedb\.org\/(movie|tv)\/(\d+)/);
    if (tmdbMatch) return { type: 'tmdb', media_type: tmdbMatch[1], id: tmdbMatch[2] };

    const imdbMatch = text.match(/imdb\.com\/title\/(tt\d+)/);
    if (imdbMatch) return { type: 'imdb', id: imdbMatch[1] };

    return null;
}
