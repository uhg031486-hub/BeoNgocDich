function execute() {
    var host = "https://timo-ngocbeo.pages.dev";
    return Response.success([
        { title: "Mới cập nhật", input: host + "/api/books?sort=updated", script: "gen.js" },
        { title: "Nhiều chương nhất", input: host + "/api/books?sort=chapters", script: "gen.js" },
        { title: "Tiên Hiệp", input: host + "/api/books?genre=Ti%C3%AAn+Hi%E1%BB%87p", script: "gen.js" },
        { title: "Huyền Huyễn", input: host + "/api/books?genre=Huy%E1%BB%81n+Huy%E1%BB%85n", script: "gen.js" },
        { title: "Đô Thị", input: host + "/api/books?genre=%C4%90%C3%B4+Th%E1%BB%8B", script: "gen.js" },
        { title: "Kiếm Hiệp", input: host + "/api/books?genre=Ki%E1%BA%BFm+Hi%E1%BB%87p", script: "gen.js" },
        { title: "Khoa Huyễn", input: host + "/api/books?genre=Khoa+Huy%E1%BB%85n", script: "gen.js" },
        { title: "Võng Du", input: host + "/api/books?genre=V%C3%B5ng+Du", script: "gen.js" },
        { title: "Đồng Nhân", input: host + "/api/books?genre=%C4%90%E1%BB%93ng+Nh%C3%A2n", script: "gen.js" },
        { title: "Quân Sự", input: host + "/api/books?genre=Qu%C3%A2n+S%E1%BB%B1", script: "gen.js" },
        { title: "Lịch Sử", input: host + "/api/books?genre=L%E1%BB%8Bch+S%E1%BB%AD", script: "gen.js" },
        { title: "Kỳ Huyễn", input: host + "/api/books?genre=K%E1%BB%B3+Huy%E1%BB%85n", script: "gen.js" }
    ]);
}
