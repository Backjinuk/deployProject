import React, {useState} from 'react';
import {Site} from '../../api/sites';

interface Props {
    site: Site;
}

// 복사할 섹션 정의
const sections = [{key: 'backup', title: '백업용'}, {key: 'deploy', title: '배포용'}, {
    key: 'restore',
    title: '원복용'
}] as const;

type SectionKey = typeof sections[number]['key'];

const PathConverter: React.FC<Props> = ({site}) => {
    const [input, setInput] = useState('');
    const [results, setResults] = useState<Record<SectionKey, string>>({
        backup: '', deploy: '', restore: ''
    });

    // 변환 로직
    const convert = () => {



        // const lines = input.split('\n').filter(Boolean);
        // const now = new Date().toISOString().slice(0, 10).replace(/-/g, '');
        // const bArr: string[] = [];
        // const dArr: string[] = [];
        // const rArr: string[] = [];
        //
        // lines.forEach(item => {
        //     let out1 = '', out2 = '', out3 = '';
        //     const map: Array<[keyof Site, keyof Site, string, string]> = [['javaOld', 'javaNew', '.java', '.class'], ['xmlOld', 'xmlNew', '', ''], ['jspOld', 'jspNew', '', ''], ['scriptOld', 'scriptNew', '', '']];
        //
        //     for (const [oldKey, newKey, extOld, extNew] of map) {
        //         const oldVal = site[oldKey] as string;
        //         const newVal = site[newKey] as string;
        //         if (oldVal && item.startsWith(oldVal)) {
        //             const file = item.substring(item.lastIndexOf('/') + 1);
        //             // 백업용
        //             out1 = `cd ${oldVal}; cp ${file.replace(extOld, extNew)} ${file.replace(extOld, extNew)}${now}`;
        //             // 배포용
        //             out2 = `cp ${site.homePath}${now}/${file.replace(extOld, extNew)} ${newVal}${file.replace(extOld, extNew)}`;
        //             // 원복용 (swap)
        //             const parts = out1.split(' ');
        //             [parts[parts.length - 2], parts[parts.length - 1]] = [parts[parts.length - 1], parts[parts.length - 2]];
        //             out3 = parts.join(' ');
        //             break;
        //         }
        //     }
        //     bArr.push(out1);
        //     dArr.push(out2);
        //     rArr.push(out3);
        // });
        //
        // setResults({
        //     backup: bArr.join('\n'), deploy: dArr.join('\n'), restore: rArr.join('\n')
        // });
    };

    // 클립보드 복사 함수
    const copyToClipboard = (key: SectionKey) => {
        const text = results[key];
        if (!text) return;
        navigator.clipboard.writeText(text)
            .then(() => alert(`${sections.find(s => s.key === key)?.title} 내용이 복사되었습니다.`))
            .catch(err => console.error('복사 실패', err));
    };

    return (
        <>
            <div className="mb-4">
                <label className="form-label fw-bold">변환할 경로</label>
                <textarea
                    className="form-control"
                    rows={4}
                    placeholder="라인 당 하나씩 입력하세요"
                    value={input}
                    onChange={e => setInput(e.target.value)}
                />
            </div>
            <button className="btn btn-success mb-4 w-100" onClick={convert}>변환하기</button>
            <div className="row gx-3 gy-4">
                {sections.map(({key, title}) => (<div className="col-md-4" key={key}>
                        <div className="card h-100 shadow-sm">
                            <div
                                className="card-header bg-primary text-white"
                                style={{cursor: 'pointer'}}
                                onClick={() => copyToClipboard(key as SectionKey)}
                            >
                                {title} (클릭시 복사)
                            </div>
                            <div className="card-body p-2">
                                <textarea
                                    className="form-control form-control-sm"
                                    rows={6}
                                    readOnly
                                    value={results[key as SectionKey]}
                                />
                            </div>
                        </div>
                    </div>
                ))}
            </div>
        </>
    );
};

export default PathConverter;