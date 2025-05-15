// src/styles/styles.ts
import { CSSProperties } from 'react';
import { theme } from './theme';

export const styles: Record<string, CSSProperties> = {
    wrapper: {
        background: theme.bgMuted,
        minHeight:  '100vh',
        width:      '100%',
        padding:    '2rem 0',
    },
    container: {
        maxWidth: '1000px',
        margin:   '0 auto',
        padding:  '0 1rem',
    },
    converterHeader: {
        display:       'flex',
        justifyContent:'space-between',
        alignItems:    'center',
        marginBottom:  '2rem',
    },
    converterHeaderTitle: {
        fontSize:   '2rem',
        fontWeight: '600',
        color:      theme.fgDefault,
    },
    headerActions: {
        display: 'flex',
        gap:     '0.75rem',
    },
    outlineBtn: {
        background:   'transparent',
        color:        theme.primary,
        padding:      '0.6rem 1.2rem',
        fontSize:     '0.95rem',
        borderRadius: theme.borderRadius,
        border:       `1px solid ${theme.primary}`,
        boxShadow:    '0 2px 6px rgba(0,0,0,0.1)',
        transition:   'background-color 0.2s, color 0.2s, transform 0.1s, box-shadow 0.2s',
    },
    logoutBtn: {
        background:   '#888888',
        color:        '#fff',
        padding:      '0.6rem 1.2rem',
        fontSize:     '0.95rem',
        borderRadius: theme.borderRadius,
        border:       'none',
        boxShadow:    '0 2px 6px rgba(0,0,0,0.1)',
        transition:   'background-color 0.2s, transform 0.1s, box-shadow 0.2s',
    },
    customCardSite: {
        background:   theme.bgSurface,
        borderRadius: theme.borderRadius,
        border:       `1px solid ${theme.border}`,
        boxShadow:    theme.shadow,
        padding:      '1.5rem',
        margin:       '0 auto 1.5rem',
        maxWidth:     '900px',
    },
    customCardPath: {
        background:   theme.bgSurface,
        borderRadius: theme.borderRadius,
        border:       `1px solid ${theme.border}`,
        boxShadow:    theme.shadow,
        padding:      '1.5rem',
        margin:       '0 auto 1.5rem',
        maxWidth:     '900px',
    },
    // ─── 상태별 스타일 ───────────────────────────────
    // Input 포커스 상태
    dateInputFocus: {
        borderColor: theme.primary,
        boxShadow:   `0 0 0 0.2rem rgba(74,144,226,0.3)`,
        outline:     'none',
    },
    // Toggle 'checked' 상태
    toggleChecked: {
        backgroundColor: theme.primary,
        borderColor:     theme.primary,
    },
    // 추출 버튼 hover
    extractBtnHover: {
        background: theme.primaryDark,
        transform:  'translateY(-2px)',
        boxShadow:  '0 4px 12px rgba(0,0,0,0.12)',
    },
    // Outline 버튼 hover
    outlineBtnHover: {
        background: theme.primary,
        color:      '#fff',
        transform:  'translateY(-2px)',
        boxShadow:  '0 4px 12px rgba(0,0,0,0.12)',
    },
    // Logout 버튼 hover
    logoutBtnHover: {
        background: 'rgba(233,77,77,0.85)',      // 선명한 빨간색
        transform:  'translateY(-2px)',
        boxShadow:  '0 4px 12px rgba(0,0,0,0.12)',
    },
};
