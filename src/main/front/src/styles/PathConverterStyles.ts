import { CSSProperties } from 'react';
import { theme } from './theme';

export const styles : Record<string, CSSProperties> = {
    // 카드 컨테이너
    customCard: {
        maxWidth:    '800px',
        margin:      '0 auto 1.5rem',
        padding:     '1.5rem',
        borderRadius: theme.borderRadius,
        border:      `1px solid ${theme.border}`,
        background:  theme.bgSurface,
        boxShadow:   theme.shadow,
    },

    // 날짜+토글 래퍼
    controlsRow: {
        display:      'flex',
        alignItems:   'center',
        flexWrap:     'wrap',
        gap:          '1rem',
        marginBottom: '2rem',  // 여백 살짝 더
    },
    separator: {
        fontSize: '1.25rem',
        color:    theme.fgDefault,
    },
    toggles: {
        marginLeft: 'auto',
        display:    'flex',
        alignItems: 'center',
        gap:        '1rem',
    },

    // Date Input 스타일 (파란색 Outline)
    dateInput: {
        padding:      '0.6rem 1rem',
        fontSize:     '1rem',
        color:        theme.primary,
        background:   'transparent',
        border:       `1px solid ${theme.primary}`,
        borderRadius: theme.borderRadius,
        outline:      'none',
    },

    // 추출하기 버튼 (Primary)
    extractBtn: {
        width:        '100%',
        background:   theme.primary,
        color:        '#fff',
        padding:      '0.75rem',
        fontSize:     '1rem',
        border:       'none',
        borderRadius: theme.borderRadius,
        boxShadow:    '0 2px 6px rgba(0,0,0,0.1)',
        transition:   'background-color 0.2s, transform 0.1s, box-shadow 0.2s',
    },
};
