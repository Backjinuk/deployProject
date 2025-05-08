import {useEffect, useState} from "react";
import { Site} from "../../api/sites";
import PathConverter from "./PathConverter";
import SiteSelector from "./StieSelector";
import 'bootstrap/dist/css/bootstrap.min.css';
import NickNameModal from "../login/NickNameModal";
import axios from "axios";
import PathModalMain from "../pathModal/PathModalMain";
import PathAddModal from "../pathModal/PathAddModal";

const ConverterMain = () => {
    const [sites, setSites] = useState<Site[]>([]);
    const [selected, setSelected] = useState<Site | null>(null);
    const [nickNameShowModal, setNickNameShowModal] = useState(true);
    const [pathModalShow, setPathModalShow] = useState(false);
    const [pathAddModalShow, setPathAddModalShow] = useState(false);


    useEffect(() => {
        var deployUser;
        if (localStorage.getItem('deployUser') != null) {
             deployUser = JSON.parse(localStorage.getItem('deployUser') || "");
            fetchData(deployUser);
        }
        deployUser ? setNickNameShowModal(false) : setNickNameShowModal(true);

    }, [nickNameShowModal]);

    const fetchData = ( deployUser : any) => {
        axios.post("/api/sites", deployUser)
            .then((res) => {
                console.log(res.data)
                setSites(res.data)
            });

    }
    const savedNick = (nick: string) => {
        axios.post('/api/login', { userName: nick }).then((res) => {
            setNickNameShowModal(false)
            localStorage.setItem('deployUser', JSON.stringify(res.data));
        });
    };

    const logout = () => {
        localStorage.clear()
        setNickNameShowModal(true)
    }


    const onPath = () => {
        setPathModalShow(pathModalShow => !pathModalShow)
    }

    const onPathAdd = () => {
        setPathAddModalShow(pathAddModalShow => !pathAddModalShow)
    }

    return (
        <div className="container py-5">
            <NickNameModal show={nickNameShowModal} onSave={savedNick}/>
            <PathModalMain show={pathModalShow} onPath={() => onPath()}/>
            <PathAddModal show={pathAddModalShow} onPathAdd={() => onPathAdd()}/>


            <div className="d-flex justify-content-between align-items-center mb-5">
                <h1 className="m-0">배포 경로 변환기</h1>
                <div style={{display: "flex", gap: "10px"}}>
                <button onClick={() => setPathAddModalShow(true)} className="btn btn-primary">
                    경로 정보 등록
                </button>
                <button onClick={() => setPathModalShow(true)} className="btn btn-success">
                    경로 정보 수정
                </button>
                <button onClick={() => logout()} className="btn btn-danger">
                    로그아웃
                </button>

                </div>
            </div>
           <div className="card mb-4 shadow-sm">

                <div className="card-body">
                    <SiteSelector
                        sites={sites}
                        selectedId={selected?.id ?? null}
                        onSelect={id => setSelected(sites.find(s => s.id === id) || null)}
                    />
                </div>
            </div>

            {selected && (
                <div className="card shadow-sm">
                    <div className="card-body">
                        <PathConverter site={selected} />
                    </div>
                </div>
            )}
        </div>
    );
};


export default ConverterMain;